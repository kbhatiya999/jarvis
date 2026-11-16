# Jarvis

A voice assistant in the J.A.R.V.I.S. persona, built on the Gemini Live API, for Android and iPhone. Both apps share their logic (memory, reminders, encrypted Jarvis-to-Jarvis messaging, the Gemini protocol) through a Kotlin Multiplatform module, so an Android Jarvis and an iPhone Jarvis can message each other. Releases are on GitHub: Android installs and updates with [Obtainium](https://obtainium.imranr.dev/), iPhone with AltStore or SideStore.

## Using it

- On first launch it asks for a Gemini API key. Get one at [Google AI Studio](https://aistudio.google.com/api-keys) and paste it in. The key is stored only on the phone, encrypted with the Android Keystore. Change it any time under **Settings → Change API key**.
- Tap **Talk** to speak, or type a message. Jarvis replies by voice (voice "Charon"), and both sides show up as text.
- The conversation is saved on the phone and fed back to Jarvis at the start of each session, so it remembers earlier chats. **Settings → Forget conversation** wipes it.
- **Active mode** (Settings → Active mode): when you open an app, Jarvis speaks up on its own. It works through your reminders, choosing and combining them itself, and passes on any messages from other Jarvises. It then listens for 8 seconds and stops if you stay silent. Setup:
  1. Turn on "Jarvis active mode" under Accessibility. Android blocks this for apps not installed from an app store. Tap the switch once so the "Restricted setting" popup appears; only after that does App info → ⋮ (top right) show **Allow restricted settings**. Allow it, then turn the switch on again. Uninstalling resets this.
  2. Flip the Active mode switch. A "Jarvis active" notification stays while it runs.
  - It speaks at most once per cooldown (30 min by default). It stays silent during quiet hours (11 pm–7 am), calls, Do Not Disturb and silent mode. Messages from other Jarvises are said aloud on the lock screen too.
  - **Answer hands-free:** after Jarvis has finished speaking (a reminder, or a message from another Jarvis), it listens for 8 seconds (enough to start talking; once you do, it waits until you finish). Just say your reply, such as "tell him I'm on my way", and Jarvis sends it. While it reads, talking over it doesn't cut it off. Under **Answering Jarvis** you can change how long it listens and whether it speaks on the lock screen.
  - **Waking Jarvis** (Active mode › Waking Jarvis, Android):
    - **"Hey Google, Jarvis wake up"** (recommended) works on the lock screen and costs no battery. Google does the listening and runs a Google Home routine that sends your phone a notification; Jarvis hides it and answers. Setup: give Jarvis **Notification access** (Active mode › Setup), then in the Google Home app: + › Automation; Name "Jarvis wake up"; Starter › Hey Google › "Jarvis wake up"; Actions › Notify › message "Jarvis wake up" (it must contain "Jarvis" and "wake up"); Save. The steps in the app have Copy buttons for the words to type. Jarvis ignores all other notifications.
    - Also: **Talk** on the "Jarvis active" notification or the **Talk to Jarvis** Quick Settings tile (both work locked), and "Hey Google, open Jarvis Talk" (Google asks you to unlock first).
    - **Phone listens for "Jarvis"** (offline with [Vosk](https://alphacephei.com/vosk/), a one-time 40 MB download; nothing leaves the phone):
      - **Only while talking** (default): **"Jarvis power down"** stops Jarvis at once, even mid-sentence, with a short acknowledgement, and "Jarvis…" cuts it off. Almost no battery.
      - **All the time:** also **"Jarvis wake up"** without Google. About 1% of a CPU core and the mic dot stays on; it drops to "only while talking" at night, below 20% battery and in Battery Saver (adjustable), and always listens while charging.
      - **Never.**
    - "Stop", "that's enough" or "never mind" end a conversation in any mode.
    - **Cutting Jarvis off:** by default, saying "Jarvis" while it speaks stops it and lets you talk ("Jarvis, make it 8 not 7"), while coughs and the TV can't. You can also choose any talking, or never.
    - **Jarvis's replies:** the instant answers ("Jarvis online. I'm listening, Sir.", "Already awake, Sir. Go ahead." when it was already up, "Powering down, Sir.") are recorded once in Jarvis's own Gemini voice. Until then Android's built-in voice reads them. Edit the lines, or add your own audio files, which are then used instead; **Export zip** / **Import zip** keep them across phones.
    - **Wake and sleep sounds:** a rising chime when Jarvis wakes, two beeps if it was already awake, and a soft falling chime when it stops listening, so you always know its state. Turn them off under Waking Jarvis.
  - **Talk without unlocking:** with active mode on, tap **Talk** on the "Jarvis active" notification, or add the **Talk to Jarvis** tile to Quick Settings (pull down twice › edit ✏️ › drag it in). Jarvis answers and listens, even on the lock screen. Without active mode, the tile opens Jarvis after you unlock.
- **Voice chat keeps going when the phone locks.** While Jarvis is listening, a "Talking to Jarvis" notification shows, and the chat carries on with the screen off or in another app. Stop it from the app or the notification.
- **Reminders** (Settings → Reminders): each person keeps their own list on their phone. It starts with water, junk and oily food, staying warm, and not getting hurt. Add or remove items there, or just tell Jarvis "remind me to stretch" or "stop reminding me about water".
- **Jarvis to Jarvis** (Settings → Contacts): set your name, then **Share** your Jarvis address with a friend who uses Jarvis, and add theirs. After that, say "tell Priya's Jarvis I'll be 10 minutes late". Their Jarvis says it aloud if active mode is on and it's a good moment. Otherwise it shows a notification and Jarvis tells them the next time it speaks up. Replies work the same way.
  - Messages are end-to-end encrypted: each Jarvis has its own key pair, and only the two of you can read a message. A message from someone not in your contacts shows up under Contacts as a request to accept or ignore.
  - They travel through [ntfy.sh](https://ntfy.sh), a free public relay that needs no account and only ever sees ciphertext. It keeps messages for about 12 hours, so a phone that stays offline longer than that misses them. Jarvis receives messages live while it's open or active mode is on, and checks every 15 minutes otherwise.

## Install with Obtainium

1. Install Obtainium (from its [GitHub releases](https://github.com/ImranR98/Obtainium/releases) or F-Droid).
2. In Obtainium choose **Add App** and paste:

   ```
   https://github.com/kbhatiya999/jarvis
   ```

3. Tap **Add**, then **Install**. Obtainium reads the latest GitHub release and downloads `jarvis-vX.Y.Z.apk`.
4. Updates: Obtainium checks for new releases in the background (or pull to refresh) and offers the update.

Every release is signed with the same key, so updates install over the existing app without losing data.

## Install on iPhone (AltStore / SideStore)

There's no Obtainium on iOS, and TestFlight needs a paid Apple developer account. The free route:

1. Install [AltStore](https://altstore.io/) or [SideStore](https://sidestore.io/) on the iPhone. They install apps with your own free Apple ID.
2. In it, add this source (Sources → +): `https://github.com/kbhatiya999/jarvis/releases/latest/download/altstore-source.json`
3. Install Jarvis from that source. It offers updates when a new release comes out.

A free Apple ID signs apps for 7 days. AltStore/SideStore re-sign Jarvis in the background (or tap Refresh) so it keeps opening.

### Active mode on iPhone

iOS doesn't let an app see which app you open, so Shortcuts does it. Set it up once (Jarvis › Settings › Active mode shows the same steps, with a button that opens Shortcuts):

1. Turn on **Active mode** in Jarvis.
2. Shortcuts › **Automation** › **+** › **App**. Pick the apps (Instagram, YouTube, ...) and keep **Is Opened** ticked.
3. Choose **Run Immediately** and turn off **Notify When Run**. Tap Next.
4. **New Blank Automation** › **Add Action**, search for **Jarvis check-in** and add it. You can type the app's name into it so Jarvis can mention it.

When you open one of those apps, Jarvis speaks up in a sentence or two over it (its sound is lowered meanwhile), using the same reminders, cooldown and quiet hours as on Android, and says any waiting messages. **Test now** on the Active mode screen lets you hear it.

Differences from Android:
- **It can't listen afterwards.** iOS doesn't allow the mic from the background. A notification shows what Jarvis said; tap it and Jarvis opens with the mic on, so you can answer.
- **Fewer "bad moment" checks.** It skips calls, but iOS doesn't tell apps about the silent switch or Focus, so quiet hours and the cooldown are what keep it quiet.
- **You pick the apps in Shortcuts.** A newly installed app needs adding to the automation.

Messages from other Jarvises arrive live while Jarvis is open. In the background, iOS checks only now and then, and you get a notification. Waiting messages are said aloud when you open Jarvis or at the next check-in.

## Develop

Tools come from [mise](https://mise.jdx.dev/), including the Android SDK. Env and secrets come from [fnox](https://fnox.jdx.dev/). [Tilt](https://tilt.dev/) runs and shows the workflow.

**Everything goes through `tilt:` tasks.** `mise run tilt:<name>` presses that button in Tilt, starting Tilt in the background if needed. It streams the log to your terminal and waits for the result, so every run also appears in the dashboard at http://localhost:10350. `mise tasks` lists only these. The individual steps are hidden mise tasks that Tilt runs. Nothing runs automatically: not on save, commit or push.

```bash
mise install                 # java, gradle, android-sdk, tilt, fnox, gh (gh publishes releases)
mise tasks                   # the tilt:* commands
mise run tilt:status         # every step and pipeline, with its last result
mise run tilt:logs [name]
```

In Tilt, resources are grouped into:

| Group | Resources |
|---|---|
| pipelines | **release** (release-check → lint → test → build → ios-release-build → release-publish) |
| setup | setup, env-check, keystore-gen, secrets-push |
| build | lint, test, build, build-ci |
| phone | devices, phone-pair, phone-connect, install |
| release | release-check, ios-release-build, release-publish, release-ci |
| ios | ios-build, ios-sim |

A pipeline presses its steps in order and stops at the first failure. Each step keeps its own log and status.

**There is one way to make an APK: the `build` step** (`scripts/build.sh`). It is always signed with the release key. `release` and `install` use it, and so do the GitHub workflows below. So a copy from Obtainium and a copy installed from your Mac can update each other. Only the version name differs:

| Made by | Version name |
|---|---|
| `tilt:release 0.6.0`, `tilt:release-ci 0.6.0` or the GitHub **Release** workflow | `v0.6.0` |
| `tilt:install`, `tilt:build`, `tilt:build-ci` or the GitHub **Build** workflow | `v0.6.0-dev+<commit>` (latest release + the commit; `.dirty` if uncommitted changes) |

After a dev install, Obtainium may show the latest release as an update. That's expected, and the next release installs normally.

### Environment (fnox)

- `fnox.toml` is committed and holds non-secret defaults.
- `fnox.local.toml` is gitignored and overrides it. Put real secrets here. For now everything is **plain text, not encrypted**.
- Variables you set yourself take precedence over both.

### Releasing

```bash
mise run tilt:release          # next patch version after the latest tag
mise run tilt:release 0.6.0    # or a version you choose
```

Everything happens on your Mac. It checks you're on `main` with a clean tree and an unused tag, lints, builds and signs the APK, then tags `vX.Y.Z` and publishes the GitHub release that Obtainium reads. In the dashboard, use **release** or its *Release version* button.

One-time setup: `mise run tilt:keystore-gen` creates the signing key in `.secrets/` and `fnox.local.toml`. Back both up: if the key is lost, installed copies can no longer be updated.

#### Local or on GitHub

```bash
mise run tilt:build              # build on this Mac
mise run tilt:build-ci           # build on GitHub Actions; APK lands in app/build/outputs/apk/ci/
mise run tilt:release [x.y.z]    # release from this Mac
mise run tilt:release-ci [x.y.z] # release on GitHub Actions
```

The `-ci` tasks push your local `main` first (GitHub builds what's on GitHub), then start the workflow and stream it in Tilt until it finishes.

#### Without the Mac (from GitHub only)

Edit the code on github.com (or github.dev) and commit to `main`. Then go to **Actions → Release → Run workflow** in the GitHub website or app, optionally with a version. It runs the same scripts as `tilt:release` (release-check → lint → build → release-publish), signed with the same key, and publishes the release Obtainium reads. **Actions → Build** makes a dev APK without releasing; download the `jarvis-apk` artifact, a zip with the APK inside.

Both workflows are manual only; nothing runs on push. They need the signing key in the repo's secrets: run `mise run tilt:secrets-push` once from the Mac, and again if the key changes.

### iOS

```bash
mise run tilt:ios-sim      # build and run in the iPhone simulator
mise run tilt:ios-build    # unsigned .ipa in ios/build/ (for AltStore/SideStore)
```

Both need Xcode (App Store; open it once, then `sudo xcode-select -s /Applications/Xcode.app`). iOS only runs the Jarvis check-in from Shortcuts for apps signed by an Apple team. For the simulator, add your free Apple ID in Xcode › Settings › Accounts, pick its Personal Team once under the Jarvis target's Signing & Capabilities, and put the team ID in `fnox.local.toml` as `IOS_TEAM_ID`; `tilt:ios-sim` then signs with it. Without it the simulator build still works, but only **Test now** runs the check-in. AltStore/SideStore installs are signed with each person's Apple ID and run it. mise provides everything else, including XcodeGen, which generates `ios/Jarvis.xcodeproj` from `ios/project.yml`. The project isn't committed. The iOS app is SwiftUI in `ios/Jarvis/`, on the shared Kotlin module in `shared/`, which Xcode builds as `JarvisCore.framework`.

Releases include the iOS app when it can be built: always on GitHub (`tilt:release-ci`, or the Release workflow, on a macOS runner), and from this Mac once Xcode is installed. Without Xcode, `tilt:release` publishes Android only and says so.

To install on your own iPhone straight from Xcode with a free Apple ID: open the generated project, pick your team under Signing & Capabilities, and run. That copy also expires after 7 days.

### Phone

```bash
mise run tilt:phone-pair <ip:port> <code>   # once: Wireless debugging -> Pair device with pairing code
mise run tilt:phone-connect                 # finds the paired phone on Wi-Fi
mise run tilt:install                       # signed dev build, installs over the Obtainium copy
```
