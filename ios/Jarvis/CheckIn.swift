import AppIntents
import CallKit
import Foundation
import JarvisCore
import UserNotifications

/// The "Jarvis check-in" Shortcuts action. iPhone active mode: a personal automation
/// ("When Instagram is opened → Jarvis check-in") runs it in the background.
struct JarvisCheckInIntent: AppIntent {
    static var title: LocalizedStringResource = "Jarvis check-in"
    static var description = IntentDescription("Jarvis speaks up if it's a good moment: your reminders and any messages from other Jarvises. Use it in a Shortcuts automation that runs when you open an app.")
    static var openAppWhenRun = false

    @Parameter(title: "App name", description: "The app you open, so Jarvis can mention it. Optional.")
    var app: String?

    static var parameterSummary: some ParameterSummary { Summary("Jarvis check-in for \(\.$app)") }

    @MainActor
    func perform() async throws -> some IntentResult {
        let outcome = await CheckIn.run(app: app)
        NSLog("Jarvis check-in: %@", outcome)
        return .result()
    }
}

struct JarvisShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: JarvisCheckInIntent(),
            phrases: ["\(.applicationName) check-in"],
            shortTitle: "Check-in",
            systemImageName: "waveform"
        )
    }
}

/// One check-in: the same rules as Android active mode (shared ActiveMode), then Jarvis speaks a short
/// line over whatever app is open (ducking its audio). iOS doesn't allow the mic from the background, so
/// Jarvis doesn't listen afterwards; a notification lets you open Jarvis to reply.
@MainActor
enum CheckIn {
    static let replyCategory = "jarvis.reply"
    private static var speaker: Speaker?

    /// Returns what happened, in words for the Active mode screen.
    static func run(app: String?, force: Bool = false) async -> String {
        let jarvis = Jarvis.companion.setUp(platform: IOSPlatform())
        let active = jarvis.active
        guard speaker == nil else { return "Jarvis is already speaking." }
        guard jarvis.apiKey.value != nil else { return "Add a Gemini API key first." }
        if !force {
            guard active.enabled else { return "Skipped: active mode is off in Jarvis." }
            guard !onCall() else { return "Skipped: you're on a call." }
            let hour = Int32(Calendar.current.component(.hour, from: Date()))
            guard active.allowed(hour: hour, ignoreCooldown: false) else { return "Skipped: cooldown or quiet hours." }
        }
        active.markSpoke()
        await poll() // messages waiting on the relay get said too
        let name = app.map { $0.trimmingCharacters(in: .whitespaces) }.flatMap { $0.isEmpty ? nil : $0 } ?? "an app"
        let prompt = active.appOpenedPrompt(appName: name, time: localTime(), canListen: false)
        let (said, problem) = await withCheckedContinuation { (done: CheckedContinuation<(String, String?), Never>) in
            let s = Speaker(jarvis: jarvis) { text, problem in
                speaker = nil
                done.resume(returning: (text, problem))
            }
            speaker = s
            s.start(systemPrompt: jarvis.systemPrompt(extra: prompt), cue: ActiveMode.companion.cue(appName: name))
        }
        guard !said.isEmpty else { return "Jarvis didn't speak: \(problem ?? "no answer from Gemini")." }
        notifyReply(said)
        return "Jarvis said: \(said)"
    }

    private static func onCall() -> Bool { CXCallObserver().calls.contains { !$0.hasEnded } }

    private static func poll() async {
        await withCheckedContinuation { (done: CheckedContinuation<Void, Never>) in
            IosBridge.shared.poll { _ in done.resume() }
        }
    }

    private static func localTime() -> String {
        let f = DateFormatter()
        f.dateFormat = "h:mm a, EEEE"
        return f.string(from: Date())
    }

    /// What Jarvis said, as a notification; tapping it opens Jarvis with the mic on.
    private static func notifyReply(_ text: String) {
        let content = UNMutableNotificationContent()
        content.title = "Jarvis"
        content.body = text
        content.subtitle = "Tap to reply"
        content.categoryIdentifier = replyCategory
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "jarvis.checkin", content: content, trigger: nil))
    }

    static func registerNotificationActions() {
        let reply = UNNotificationAction(identifier: "reply", title: "Reply", options: [.foreground])
        let category = UNNotificationCategory(identifier: replyCategory, actions: [reply], intentIdentifiers: [])
        UNUserNotificationCenter.current().setNotificationCategories([category])
    }
}

/// A speak-only voice session: connects, lets Jarvis say its piece, waits for the audio to finish, closes.
@MainActor
private final class Speaker: NSObject, LiveSessionListener {
    private let jarvis: Jarvis
    private let done: (String, String?) -> Void
    private var problem: String?
    private var session: LiveSession?
    private var writer: Conversation.Writer?
    private var text = ""
    private var finished = false

    init(jarvis: Jarvis, done: @escaping (String, String?) -> Void) {
        self.jarvis = jarvis
        self.done = done
    }

    func start(systemPrompt: String, cue: String) {
        writer = jarvis.conversation.createWriter()
        let s = jarvis.createSession(systemPrompt: systemPrompt, listener: self, speakOnly: true, interruptible: true)
        session = s
        s.connect()
        s.sendText(text: cue) // queued until the session is ready; not stored in memory
        // iOS gives a background Shortcuts action limited time; never hang.
        DispatchQueue.main.asyncAfter(deadline: .now() + 25) { [weak self] in self?.finish() }
    }

    private func finish() {
        guard !finished else { return }
        finished = true
        writer?.closeTurn()
        session?.close()
        session = nil
        done(text.trimmingCharacters(in: .whitespacesAndNewlines), problem)
    }

    nonisolated func onReady() {}
    nonisolated func onUserText(delta: String) {}
    nonisolated func onInterrupted() {}
    nonisolated func onEndRequested() {} // speak-only: it ends after its turn anyway

    nonisolated func onJarvisText(delta: String) {
        MainActor.assumeIsolated {
            text += delta
            writer?.jarvisDelta(delta: delta)
        }
    }

    nonisolated func onTurnComplete() {
        MainActor.assumeIsolated {
            // Let the queued audio play out, then hang up.
            let wait = Double(session?.playbackRemainingMs() ?? 0) / 1000 + 0.3
            DispatchQueue.main.asyncAfter(deadline: .now() + wait) { [weak self] in self?.finish() }
        }
    }

    nonisolated func onClosed(reason: String?) {
        MainActor.assumeIsolated {
            if let reason {
                NSLog("Jarvis check-in closed: %@", reason)
                problem = reason
            }
            finish()
        }
    }
}
