import BackgroundTasks
import SwiftUI
import UserNotifications
import JarvisCore

/// Notification taps (the "Tap to reply" one after a check-in).
final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    @MainActor static var onReply: (() -> Void)?

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        return true
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        guard response.notification.request.content.categoryIdentifier == CheckIn.replyCategory else { return }
        // Give the app a moment to come to the front before opening the mic.
        try? await Task.sleep(for: .milliseconds(500))
        await MainActor.run { AppDelegate.onReply?() }
    }
}

@main
struct JarvisApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @StateObject private var model: AppModel
    @Environment(\.scenePhase) private var phase
    private static let pollTask = "com.kbhatiya999.jarvis.relay-poll"

    init() {
        let jarvis = Jarvis.companion.setUp(platform: IOSPlatform())
        let model = AppModel(jarvis: jarvis)
        _model = StateObject(wrappedValue: model)
        AppDelegate.onReply = { model.replyFromNotification() }
        CheckIn.registerNotificationActions()
        // Background check of the relay for messages; iOS decides when it actually runs.
        BGTaskScheduler.shared.register(forTaskWithIdentifier: Self.pollTask, using: nil) { task in
            Self.schedulePoll()
            IosBridge.shared.poll { ok in task.setTaskCompleted(success: ok.boolValue) }
        }
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }

    static func schedulePoll() {
        let request = BGAppRefreshTaskRequest(identifier: pollTask)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        try? BGTaskScheduler.shared.submit(request)
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
                .preferredColorScheme(.dark)
                .tint(Theme.accent)
        }
        .onChange(of: phase) { _, newPhase in
            switch newPhase {
            case .active: model.setForeground(true)
            case .background:
                model.setForeground(false)
                Self.schedulePoll()
            default: break
            }
        }
    }
}

enum Theme {
    static let accent = Color(red: 0x4F / 255, green: 0xC3 / 255, blue: 0xF7 / 255)
    static let background = Color(red: 0x0B / 255, green: 0x12 / 255, blue: 0x20 / 255)
    static let bubble = Color(red: 0x1E / 255, green: 0x2A / 255, blue: 0x44 / 255)
    static let relayText = Color(red: 1, green: 0xE0 / 255, blue: 0x82 / 255)
    static let relayBubble = Color(red: 0x2A / 255, green: 0x2A / 255, blue: 0x1E / 255)
}
