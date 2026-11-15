import SwiftUI
import JarvisCore

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
                .onChange(of: model.messages.count) { _ in scroll(proxy) }
                .onChange(of: model.messages.last?.text) { _ in scroll(proxy) }
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
                    NavigationLink("Reminders") { RemindersView() }
                    NavigationLink(model.requests.isEmpty ? "Contacts" : "Contacts (\(model.requests.count) new)") { ContactsView() }
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
                    .onChange(of: name) { v in book.updateMyName(name: String(v.prefix(40))) }
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
                    .onChange(of: newAddress) { v in
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
