import Foundation
import Security
import UserNotifications
import JarvisCore

/// iOS side of the shared Jarvis logic: UserDefaults, Keychain, files, notifications, audio.
final class IOSPlatform: NSObject, Platform {
    let secrets: KeyValueStore = KeychainStore()
    private let dir: URL = {
        let url = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("Jarvis")
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }()

    func store(namespace: String) -> KeyValueStore { DefaultsStore(prefix: namespace) }

    func readFile(name: String) -> String? { try? String(contentsOf: dir.appendingPathComponent(name), encoding: .utf8) }

    func writeFile(name: String, text: String) {
        try? text.write(to: dir.appendingPathComponent(name), atomically: true, encoding: .utf8)
    }

    func deleteFile(name: String) { try? FileManager.default.removeItem(at: dir.appendingPathComponent(name)) }

    func notify(title: String, text: String) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = text
        content.sound = .default
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil))
    }

    func createAudio(speakOnly: Bool) -> AudioIO { IOSAudio(speakOnly: speakOnly) }
    func nowMillis() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
    func log(message: String) { NSLog("Jarvis: %@", message) }
}

/// One namespace in UserDefaults ("<namespace>.<key>").
private final class DefaultsStore: NSObject, KeyValueStore {
    private let prefix: String
    init(prefix: String) { self.prefix = prefix }
    func get(key: String) -> String? { UserDefaults.standard.string(forKey: "\(prefix).\(key)") }
    func set(key: String, value: String?) {
        if let value { UserDefaults.standard.set(value, forKey: "\(prefix).\(key)") }
        else { UserDefaults.standard.removeObject(forKey: "\(prefix).\(key)") }
    }
}

/// Secrets (API key, private key) in the Keychain, this device only.
private final class KeychainStore: NSObject, KeyValueStore {
    private func query(_ key: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "com.kbhatiya999.jarvis", kSecAttrAccount as String: key]
    }

    func get(key: String) -> String? {
        var q = query(key)
        q[kSecReturnData as String] = true
        var out: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let data = out as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    func set(key: String, value: String?) {
        SecItemDelete(query(key) as CFDictionary)
        guard let value else { return }
        var q = query(key)
        q[kSecValueData as String] = Data(value.utf8)
        q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(q as CFDictionary, nil)
    }
}
