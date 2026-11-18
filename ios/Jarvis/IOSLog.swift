import Foundation
import JarvisCore

/// iOS side of JarvisLog: every line goes to the system log and to a rolling file (about 1 MB, oldest lines
/// dropped). The level is what the user chose under Settings › Logs, else the one this build was made with
/// (JarvisLogLevel in Info.plist, from scripts/ios-build.sh: info for releases, debug otherwise). The file only
/// leaves the phone when the user taps Share.
final class IOSLog: NSObject, LogSink {
    static let shared = IOSLog()
    static let defaultSetting = "default"
    private static let key = "jarvis_log.level"

    private let queue = DispatchQueue(label: "jarvis-log")
    private var size: Int = -1
    private var installed = false
    private let stamp: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "MM-dd HH:mm:ss.SSS"
        return f
    }()

    let file: URL = {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Jarvis/logs")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("jarvis.log")
    }()

    /// Call before Jarvis.setUp; safe to call many times.
    func install() {
        guard !installed else { return }
        installed = true
        JarvisLog.shared.sink = self
        JarvisLog.shared.level = effectiveLevel()
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
        JarvisLog.shared.i(tag: "App", message: "start \(version), iOS \(UIDeviceInfo.version), log level \(JarvisLog.shared.level.name)" +
            " (\(setting() == Self.defaultSetting ? "release default" : "chosen"))")
    }

    /// What this build logs unless the user chose otherwise.
    var releaseDefault: String? { Bundle.main.infoDictionary?["JarvisLogLevel"] as? String }

    func releaseLevel() -> LogLevel { JarvisLog.shared.resolveLevel(setting: nil, releaseDefault: releaseDefault) }

    /// "default" (follow the release) or a level name.
    func setting() -> String {
        let saved = UserDefaults.standard.string(forKey: Self.key)
        return LogLevel.companion.parse(text: saved) != nil ? saved!.lowercased() : Self.defaultSetting
    }

    func setSetting(_ value: String) {
        UserDefaults.standard.set(value, forKey: Self.key)
        JarvisLog.shared.level = effectiveLevel()
        JarvisLog.shared.i(tag: "App", message: "log level set to \(JarvisLog.shared.level.name)")
    }

    private func effectiveLevel() -> LogLevel {
        JarvisLog.shared.resolveLevel(setting: setting(), releaseDefault: releaseDefault)
    }

    func write(level: LogLevel, tag: String, message: String) {
        NSLog("Jarvis%@: %@", tag, message)
        let line = JarvisLog.shared.line(timestamp: stamp.string(from: Date()), level: level, tag: tag, message: message)
        queue.async { self.append(line) }
    }

    private func append(_ line: String) {
        let data = Data((line + "\n").utf8)
        if size < 0 { size = (try? FileManager.default.attributesOfItem(atPath: file.path)[.size] as? Int) ?? 0 }
        if let h = try? FileHandle(forWritingTo: file) {
            h.seekToEndOfFile()
            h.write(data)
            try? h.close()
        } else {
            try? data.write(to: file)
        }
        size += data.count
        if size > Int(JarvisLog.shared.FILE_MAX), let text = try? String(contentsOf: file, encoding: .utf8) {
            let kept = JarvisLog.shared.trim(text: text, max: JarvisLog.shared.FILE_MAX, keep: JarvisLog.shared.FILE_MAX / 4 * 3)
            try? kept.write(to: file, atomically: true, encoding: .utf8)
            size = kept.utf8.count
        }
    }

    /// Size of the log in bytes (after the lines queued so far are written).
    func bytes() -> Int {
        queue.sync {}
        return (try? FileManager.default.attributesOfItem(atPath: file.path)[.size] as? Int) ?? 0
    }

    func clear() {
        queue.sync {
            try? FileManager.default.removeItem(at: file)
            size = 0
        }
        JarvisLog.shared.i(tag: "App", message: "log cleared")
    }

    /// A copy of the log with a short header about the app and phone, for the share sheet; nil when empty.
    func shareFile() -> URL? {
        queue.sync {}
        guard let log = try? String(contentsOf: file, encoding: .utf8), !log.isEmpty else { return nil }
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
        let out = file.deletingLastPathComponent().appendingPathComponent("jarvis-log.txt")
        let header = "Jarvis \(version)\niOS \(UIDeviceInfo.version), \(UIDeviceInfo.model)\n" +
            "Language \(Self.languageCode), log level \(JarvisLog.shared.level.name)\n\n"
        try? (header + log).write(to: out, atomically: true, encoding: .utf8)
        return out
    }

    /// The phone's language as a BCP-47 code ("en-IN"), or "en-IN" when it can't tell.
    static var languageCode: String { Locale.preferredLanguages.first ?? "en-IN" }
}

/// Device facts for the log header, without importing UIKit here.
private enum UIDeviceInfo {
    static var version: String { ProcessInfo.processInfo.operatingSystemVersionString }
    static var model: String {
        var info = utsname()
        uname(&info)
        return withUnsafeBytes(of: &info.machine) { String(decoding: $0.prefix(while: { $0 != 0 }), as: UTF8.self) }
    }
}
