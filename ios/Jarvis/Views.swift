import SwiftUI
import JarvisCore
import UniformTypeIdentifiers

struct RootView: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        ZStack {
            Theme.background.ignoresSafeArea()
            if model.apiKey == nil || model.editingKey { KeyView() } else { NavigationStack { ChatView() } }
        }
    }
}

// MARK: - API key

struct KeyView: View {
    @EnvironmentObject var model: AppModel
    @State private var key = ""
    @State private var show = false

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("J.A.R.V.I.S.").font(.largeTitle.bold()).foregroundStyle(Theme.accent)
            Text(model.apiKey == nil ? "Systems require a Gemini API key, Sir." : "Replace the Gemini API key.")
            VStack(alignment: .leading, spacing: 4) {
                Text("1. Open Google AI Studio and sign in.")
                Text("2. Tap \"Create API key\" and copy it.")
                Text("3. Paste it below.")
            }
            Link("Open Google AI Studio", destination: URL(string: "https://aistudio.google.com/api-keys")!)
            HStack {
                Group {
                    if show { TextField("AIza...", text: $key) } else { SecureField("AIza...", text: $key) }
                }
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                Button(show ? "Hide" : "Show") { show.toggle() }
            }
            .textFieldStyle(.roundedBorder)
            if let current = model.apiKey { Text("Current key ends in ...\(String(current.suffix(4)))").font(.caption).foregroundStyle(.gray) }
            Text("Stored only on this device, in the Keychain.").font(.caption).foregroundStyle(.gray)
            HStack {
                Button("Save") { model.saveKey(key) }.buttonStyle(.borderedProminent).disabled(key.trimmingCharacters(in: .whitespaces).isEmpty)
                if model.apiKey != nil { Button("Cancel") { model.editingKey = false } }
            }
        }
        .padding(24)
    }
}

// MARK: - Chat

struct ChatView: View {
    @EnvironmentObject var model: AppModel
    @State private var input = ""
    @State private var confirmForget = false

    var body: some View {
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 8) {
                        if model.messages.isEmpty {
                            Text("All systems operational. Tap Talk or type a message.").foregroundStyle(.gray).padding()
                        }
                        ForEach(Array(model.messages.enumerated()), id: \.offset) { i, m in Bubble(message: m).id(i) }
                    }
                    .padding(12)
                }
                .onChange(of: model.messages.count) { scroll(proxy) }
                .onChange(of: model.messages.last?.text) { scroll(proxy) }
            }
            HStack(alignment: .bottom) {
                TextField("Message Jarvis", text: $input, axis: .vertical)
                    .lineLimit(1...4)
                    .textFieldStyle(.roundedBorder)
                    .onSubmit(send)
                if input.trimmingCharacters(in: .whitespaces).isEmpty {
                    Button(model.listening ? "Stop" : "Talk") { model.toggleVoice() }
                        .buttonStyle(.borderedProminent)
                        .tint(model.listening ? .red : Theme.accent)
                } else {
                    Button("Send", action: send).buttonStyle(.borderedProminent)
                }
            }
            .padding(12)
        }
        .background(Theme.background)
        .toolbar {
            ToolbarItem(placement: .principal) {
                VStack(spacing: 0) {
                    Text("J.A.R.V.I.S.").font(.headline).foregroundStyle(Theme.accent)
                    Text(model.status).font(.caption2).foregroundStyle(model.connected ? Theme.accent : .gray)
                }
            }
            ToolbarItem(placement: .topBarTrailing) {
                Menu(model.requests.isEmpty ? "Settings" : "Settings •") {
                    NavigationLink("Active mode") { ActiveModeView() }
                    NavigationLink("Reminders") { RemindersView() }
                    NavigationLink(model.requests.isEmpty ? "Contacts" : "Contacts (\(model.requests.count) new)") { ContactsView() }
                    NavigationLink("Connect Gemini") { GeminiView() }
                    NavigationLink("Logs") { LogsView() }
                    Button("Change API key") { model.editingKey = true }
                    Button("Forget conversation", role: .destructive) { confirmForget = true }
                }
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .alert("Forget conversation?", isPresented: $confirmForget) {
            Button("Forget", role: .destructive) { model.clearMemory() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Jarvis will lose its memory of everything said so far. This can't be undone.")
        }
    }

    private func send() {
        model.sendText(input)
        input = ""
    }

    private func scroll(_ proxy: ScrollViewProxy) {
        guard !model.messages.isEmpty else { return }
        withAnimation { proxy.scrollTo(model.messages.count - 1, anchor: .bottom) }
    }
}

struct Bubble: View {
    let message: Message

    var body: some View {
        if message.isRelay {
            Text(message.text)
                .font(.footnote)
                .foregroundStyle(Theme.relayText)
                .padding(.horizontal, 10).padding(.vertical, 6)
                .background(Theme.relayBubble, in: RoundedRectangle(cornerRadius: 10))
                .frame(maxWidth: .infinity)
        } else {
            HStack {
                if message.fromUser { Spacer(minLength: 40) }
                Text(message.text)
                    .foregroundStyle(message.fromUser ? .black : .white)
                    .padding(.horizontal, 12).padding(.vertical, 8)
                    .background(message.fromUser ? Theme.accent : Theme.bubble, in: RoundedRectangle(cornerRadius: 14))
                if !message.fromUser { Spacer(minLength: 40) }
            }
        }
    }
}

// MARK: - Reminders

struct RemindersView: View {
    @EnvironmentObject var model: AppModel
    @State private var input = ""

    var body: some View {
        List {
            Section {
                Text("Jarvis keeps these in mind and brings them up when it fits. You can also just ask it: \"remind me to stretch\" or \"stop reminding me about water\".")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            Section("Reminders") {
                if model.reminders.isEmpty { Text("None.").foregroundStyle(.secondary) }
                ForEach(Array(model.reminders.enumerated()), id: \.offset) { _, r in Text(r) }
                    .onDelete { idx in idx.sorted(by: >).forEach { model.jarvis.reminders.removeAt(index: Int32($0)) } }
            }
            Section {
                HStack {
                    TextField("e.g. taking my vitamins", text: $input).onSubmit(add)
                    Button("Add", action: add).disabled(input.trimmingCharacters(in: .whitespaces).isEmpty)
                }
                Button("Reset to the default four") { model.jarvis.reminders.resetToDefaults() }
            }
        }
        .navigationTitle("Reminders")
    }

    private func add() {
        if model.jarvis.reminders.add(text: input) { input = "" }
    }
}

// MARK: - Contacts

struct ContactsView: View {
    @EnvironmentObject var model: AppModel
    @State private var name = ""
    @State private var newName = ""
    @State private var newAddress = ""
    @State private var error: String?

    private var book: Contacts { model.jarvis.contacts }
    private var me: String { model.jarvis.identity.address }

    var body: some View {
        List {
            Section {
                Text("Add a friend's Jarvis, then ask yours: \"tell Priya's Jarvis I'll be late\". Their Jarvis passes it on. Messages are end-to-end encrypted and carried by the free ntfy.sh relay, which keeps them up to 12 hours. On iPhone, messages arrive while Jarvis is open; in the background iOS checks only now and then.")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            Section("Your Jarvis") {
                TextField("Your name (what friends' Jarvises call you)", text: $name)
                    .onChange(of: name) { _, v in book.updateMyName(name: String(v.prefix(40))) }
                Text(me).font(.system(.caption2, design: .monospaced)).foregroundStyle(.secondary).textSelection(.enabled)
                ShareLink("Share your Jarvis address", item: book.shareText(myAddress: me))
                Button("Copy") { UIPasteboard.general.string = book.shareText(myAddress: me) }
            }
            if !model.requests.isEmpty {
                Section("Requests") {
                    ForEach(Array(model.requests.enumerated()), id: \.offset) { _, r in
                        VStack(alignment: .leading, spacing: 6) {
                            Text("\(r.name)'s Jarvis (not in your contacts) says:").font(.footnote).foregroundStyle(Theme.relayText)
                            Text(r.text)
                            HStack {
                                Button("Accept") { model.jarvis.acceptRequest(request: r) }
                                Spacer()
                                Button("Ignore", role: .destructive) { book.dismissRequest(request: r) }
                            }
                            .buttonStyle(.borderless)
                        }
                    }
                }
            }
            Section("Add a contact") {
                TextField("Paste what they shared", text: $newAddress, axis: .vertical)
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                    .onChange(of: newAddress) { _, v in
                        error = nil
                        if newName.isEmpty, let n = book.extractName(text: v) { newName = n }
                    }
                TextField("Name", text: $newName)
                if let error { Text(error).foregroundStyle(.red).font(.footnote) }
                Button("Add") {
                    error = book.extractAddress(text: newAddress) == me ? "That's your own address." : book.add(name: newName, address: newAddress)
                    if error == nil { newName = ""; newAddress = "" }
                }
                .disabled(newAddress.isEmpty)
            }
            Section("Contacts") {
                if model.contacts.isEmpty { Text("None yet.").foregroundStyle(.secondary) }
                ForEach(Array(model.contacts.enumerated()), id: \.offset) { _, c in Text(c.name) }
                    .onDelete { idx in idx.map { model.contacts[$0] }.forEach { book.remove(contact: $0) } }
            }
        }
        .navigationTitle("Contacts")
        .onAppear { name = model.myName }
    }
}

// MARK: - Active mode

struct ActiveModeView: View {
    @EnvironmentObject var model: AppModel
    @State private var enabled = false
    @State private var cooldown: Int32 = ActiveMode.companion.DEFAULT_COOLDOWN
    @State private var quiet = true
    @State private var testing = false
    @State private var result: String?

    private var active: ActiveMode { model.jarvis.active }
    private let presets: [(Int32, String)] = [
        (0, "Every app open"), (300, "5 minutes"), (900, "15 minutes"), (1800, "30 minutes"), (3600, "1 hour"), (7200, "2 hours"),
    ]

    var body: some View {
        List {
            Section {
                Text("When you open an app you choose, Jarvis speaks up on its own: your reminders and any messages from other Jarvises, in a sentence or two, over the app (its sound is lowered meanwhile). Then a notification lets you open Jarvis to reply.")
                    .font(.footnote).foregroundStyle(.secondary)
                Toggle("Active mode", isOn: $enabled)
                    .onChange(of: enabled) { _, v in active.enabled = v }
            }
            Section {
                VStack(alignment: .leading, spacing: 6) {
                    Text("1. Open Shortcuts › Automation › + (New Automation).")
                    Text("2. Choose App, pick the apps (e.g. Instagram, YouTube), keep \"Is Opened\" ticked.")
                    Text("3. Choose Run Immediately and turn off Notify When Run. Tap Next.")
                    Text("4. Tap New Blank Automation › Add Action, search for \"Jarvis check-in\" and add it. Optionally type the app's name into it.")
                }
                .font(.footnote)
                Button("Open Shortcuts") { UIApplication.shared.open(URL(string: "shortcuts://")!) }
            } header: {
                Text("Set up once in Shortcuts")
            } footer: {
                Text("iOS doesn't let apps see what you open, so Shortcuts does it. Add more apps to the automation any time.")
            }
            Section("How often") {
                Picker("Speak at most once every", selection: $cooldown) {
                    ForEach(presets, id: \.0) { Text($0.1).tag($0.0) }
                    if !presets.contains(where: { $0.0 == cooldown }) { Text("\(cooldown) seconds").tag(cooldown) }
                }
                .onChange(of: cooldown) { _, v in active.cooldownSeconds = v }
                Toggle("Quiet hours (11 pm–7 am)", isOn: $quiet)
                    .onChange(of: quiet) { _, v in active.quietHours = v }
            }
            Section {
                Button(testing ? "Speaking…" : "Test now") {
                    testing = true
                    model.endSession()
                    Task {
                        result = await CheckIn.run(app: "Jarvis", force: true)
                        testing = false
                    }
                }
                .disabled(testing)
                if let result { Text(result).font(.footnote).foregroundStyle(.secondary) }
            } footer: {
                Text("Speaks once now, ignoring the cooldown, so you can hear it.")
            }
            Section("Compared with Android") {
                Text("Jarvis can't listen after speaking (iOS doesn't allow the mic from the background): tap its notification to reply. It skips calls, but iOS doesn't tell apps about the silent switch or Focus, so quiet hours and the cooldown are what keep it quiet.")
                    .font(.footnote).foregroundStyle(.secondary)
            }
        }
        .navigationTitle("Active mode")
        .onAppear {
            enabled = active.enabled
            cooldown = active.cooldownSeconds
            quiet = active.quietHours
        }
    }
}

/// Settings › Logs: how much Jarvis logs, and sharing the log (e.g. by email) when something goes wrong.
struct LogsView: View {
    @State private var level = IOSLog.shared.setting()
    @State private var bytes = IOSLog.shared.bytes()
    @State private var shareURL: URL? = IOSLog.shared.shareFile()

    var body: some View {
        Form {
            Section {
                Picker("Level", selection: $level) {
                    Text("Default (\(IOSLog.shared.releaseLevel().name.lowercased()))").tag(IOSLog.defaultSetting)
                    Text("Errors only").tag("error")
                    Text("Warnings").tag("warn")
                    Text("Info").tag("info")
                    Text("Debug (most detail)").tag("debug")
                }
                .onChange(of: level) { _, value in IOSLog.shared.setSetting(value) }
            } footer: {
                Text("Debug also keeps the first words of what you and Jarvis said. The log stays on this phone until you share it.")
            }
            Section {
                Text("Log size: \(bytes / 1024) KB")
                if let shareURL {
                    ShareLink("Share log", item: shareURL)
                } else {
                    Text("Nothing logged yet.").foregroundStyle(.secondary)
                }
                Button("Clear", role: .destructive) {
                    IOSLog.shared.clear()
                    bytes = IOSLog.shared.bytes()
                    shareURL = IOSLog.shared.shareFile()
                }
            }
        }
        .navigationTitle("Logs")
        .onAppear {
            bytes = IOSLog.shared.bytes()
            shareURL = IOSLog.shared.shareFile()
        }
    }
}

/// Settings › Connect Gemini: the link to paste into the Gemini app (Connectors › + Custom), so Gemini can call
/// tell_jarvis and Jarvis says the result. See shared GeminiBridge and bridge/ in the repo.
struct GeminiView: View {
    @EnvironmentObject var model: AppModel
    @State private var version = 0
    @State private var custom = ""
    @State private var confirmNew = false
    @State private var copied = false

    private var bridge: GeminiBridge { model.jarvis.bridge }

    var body: some View {
        let _ = version
        Form {
            Section {
                Text("Let the Gemini app talk through Jarvis. Gemini does the work, then calls \"tell_jarvis\" and Jarvis says the result, as a message from \"Gemini\".")
                    .foregroundStyle(.secondary)
            }
            if bridge.baseUrl.isEmpty {
                Section { Text("This build has no bridge address. Enter one below.").foregroundStyle(.orange) }
            } else {
                Section {
                    Button(bridge.connected ? "Copy link" : "Make and copy link") { copy(bridge.link()) }
                    if bridge.connected {
                        Button("New link (turns the old one off)") { confirmNew = true }
                        Button("Disconnect Gemini", role: .destructive) { bridge.disconnect(); version += 1 }
                    }
                    if copied { Text("Copied.").foregroundStyle(.secondary) }
                } header: { Text("1. Your link") } footer: {
                    Text("Anyone with this link can make your Jarvis speak (always as \"Gemini\"). Keep it to yourself.")
                }
                Section("2. Add it in Gemini") {
                    Text("Gemini › Settings › Connectors › + Custom › paste the link as MCP Server URL › leave Client ID and secret empty › Next.")
                }
                Section("3. Tell Gemini when to use it (optional)") {
                    Text(GeminiBridge.companion.GEM_INSTRUCTION).font(.footnote).foregroundStyle(.secondary)
                    Button("Copy instruction") { UIPasteboard.general.string = GeminiBridge.companion.GEM_INSTRUCTION }
                    Text("Try it: ask Gemini \"Tell my Jarvis hello\".")
                }
            }
            Section {
                TextField("https://…", text: $custom).textInputAutocapitalization(.never).autocorrectionDisabled()
                Button("Save address") {
                    bridge.customUrl = custom
                    if bridge.connected { bridge.disconnect() }
                    version += 1
                }
                .disabled(!(custom.isEmpty || custom.hasPrefix("https://")) || custom.trimmingCharacters(in: .whitespaces) == bridge.customUrl)
            } header: { Text("Bridge address") } footer: {
                Text("Empty uses the one this version came with. Changing it needs a new link.")
            }
        }
        .navigationTitle("Connect Gemini")
        .onAppear { custom = bridge.customUrl }
        .alert("Make a new link?", isPresented: $confirmNew) {
            Button("New link") { copy(bridge.doNewLink()) }
            Button("Cancel", role: .cancel) {}
        } message: { Text("The old link stops working at once; paste the new one into Gemini.") }
    }

    private func copy(_ link: String?) {
        guard let link else { return }
        UIPasteboard.general.setItems([[UTType.plainText.identifier: link]], options: [.expirationDate: Date().addingTimeInterval(600)])
        copied = true
        version += 1
    }
}
