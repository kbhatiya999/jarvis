import AVFoundation
import Foundation
import JarvisCore

/// SwiftUI state over the shared Jarvis, plus the voice session (like the Android ViewModel).
@MainActor
final class AppModel: ObservableObject {
    let jarvis: Jarvis
    @Published var apiKey: String?
    @Published var messages: [Message] = []
    @Published var reminders: [String] = []
    @Published var contacts: [Contact] = []
    @Published var requests: [ContactRequest] = []
    @Published var myName = ""
    @Published var status = "Standing by"
    @Published var connected = false
    @Published var listening = false
    @Published var editingKey = false

    private var subscriptions: [Subscription] = []
    private var session: LiveSession?
    private var token = NSObject()
    private var writer: Conversation.Writer

    init(jarvis: Jarvis) {
        self.jarvis = jarvis
        writer = jarvis.conversation.createWriter()
        subscriptions = [
            jarvis.apiKey.watch { [weak self] v in self?.apiKey = v as String? },
            jarvis.conversation.messages.watch { [weak self] v in self?.messages = (v as? [Message]) ?? [] },
            jarvis.reminders.items.watch { [weak self] v in self?.reminders = (v as? [String]) ?? [] },
            jarvis.contacts.contacts.watch { [weak self] v in self?.contacts = (v as? [Contact]) ?? [] },
            jarvis.contacts.requests.watch { [weak self] v in self?.requests = (v as? [ContactRequest]) ?? [] },
            jarvis.contacts.myName.watch { [weak self] v in self?.myName = (v as String?) ?? "" },
        ]
        jarvis.onMessageArrived = { [weak self] in self?.relayPending() }
    }

    private var streaming = false

    /// Messages arrive live while Jarvis is open; waiting ones are fetched and said on opening.
    func setForeground(_ on: Bool) {
        let relay = jarvis.relay
        if on && !streaming {
            streaming = true
            relay.acquireStream()
            relay.pollAsync()
            relayPending()
        } else if !on && streaming {
            streaming = false
            relay.releaseStream()
        }
    }

    func saveKey(_ key: String) {
        jarvis.saveApiKey(key: key)
        editingKey = false
        endSession()
    }

    func clearMemory() {
        endSession()
        jarvis.conversation.clear()
    }

    func toggleVoice() {
        if listening {
            session?.stopMic()
            listening = false
            status = "Listening paused"
            return
        }
        AVAudioSession.sharedInstance().requestRecordPermission { granted in
            Task { @MainActor in
                guard granted else { self.status = "Microphone access is off (Settings › Jarvis)"; return }
                self.ensureSession().startMic()
                self.listening = true
                self.status = self.connected ? "Listening" : "Connecting"
            }
        }
    }

    func sendText(_ text: String) {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return }
        writer.addUser(text: t)
        ensureSession().sendText(text: t)
    }

    func endSession() {
        token = NSObject()
        session?.close()
        session = nil
        connected = false
        listening = false
        writer.closeTurn()
    }

    /// Messages from other Jarvises that weren't said yet: Jarvis says them now (app is open).
    func relayPending() {
        guard apiKey != nil else { return }
        let pending = jarvis.inbox.takePending()
        guard !pending.isEmpty else { return }
        let time = DateFormatter.localizedString(from: Date(), dateStyle: .none, timeStyle: .short)
        let prompt = Prompts.shared.relay(pending: pending, time: time)
        if let s = session {
            s.sendText(text: "(New message just arrived: \(pending.joined(separator: " | ")). Relay it to the user now.)")
        } else {
            startSession(systemPrompt: jarvis.systemPrompt(extra: prompt)).sendText(text: "(Messages just arrived. Relay them now.)")
        }
    }

    private func ensureSession() -> LiveSession {
        if let s = session { return s }
        return startSession(systemPrompt: jarvis.systemPrompt(extra: nil))
    }

    private func startSession(systemPrompt: String) -> LiveSession {
        status = "Connecting"
        let t = NSObject()
        token = t
        writer = jarvis.conversation.createWriter()
        let s = jarvis.createSession(systemPrompt: systemPrompt, listener: Callbacks(model: self, token: t))
        session = s
        s.connect()
        return s
    }

    fileprivate func handle(_ token: NSObject, _ event: (AppModel) -> Void) {
        if token === self.token { event(self) }
    }

    private final class Callbacks: NSObject, LiveSessionListener {
        weak var model: AppModel?
        let token: NSObject
        init(model: AppModel, token: NSObject) { self.model = model; self.token = token }

        private func on(_ event: @escaping (AppModel) -> Void) {
            MainActor.assumeIsolated { model?.handle(token, event) }
        }

        func onReady() { on { m in m.connected = true; m.status = m.listening ? "Listening" : "Online" } }
        func onUserText(delta: String) { on { $0.writer.userDelta(delta: delta) } }
        func onJarvisText(delta: String) { on { $0.writer.jarvisDelta(delta: delta) } }
        func onTurnComplete() { on { $0.writer.closeTurn() } }
        func onInterrupted() { on { $0.writer.closeTurn() } }
        func onClosed(reason: String?) {
            on { m in
                m.writer.closeTurn()
                m.session = nil
                m.connected = false
                m.listening = false
                m.status = reason ?? "Standing by"
            }
        }
    }
}
