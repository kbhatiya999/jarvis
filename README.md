# Jarvis

A voice assistant in the J.A.R.V.I.S. persona, built on the Gemini Live API. It's distributed through GitHub Releases and installable/updatable with [Obtainium](https://obtainium.imranr.dev/).

## Using it

- On first launch it asks for a Gemini API key. Get one at [Google AI Studio](https://aistudio.google.com/api-keys) and paste it in. The key is stored only on the phone, encrypted with the Android Keystore. Change it any time under **Settings → Change API key**.
- Tap **Talk** to speak, or type a message. Jarvis replies by voice (voice "Charon"), and both sides show up as text.
- The conversation is saved on the phone and fed back to Jarvis at the start of each session, so it remembers earlier chats. **Settings → Forget conversation** wipes it.
- **Active mode** (Settings → Active mode): when you open an app, Jarvis speaks up on its own. It works through your reminders, choosing and combining them itself, and passes on any messages from other Jarvises. It then listens for 5 seconds and stops if you stay silent. Setup:
  1. Turn on "Jarvis active mode" under Accessibility. Android blocks this for apps not installed from an app store. Tap the switch once so the "Restricted setting" popup appears; only after that does App info → ⋮ (top right) show **Allow restricted settings**. Allow it, then turn the switch on again. Uninstalling resets this.
  2. Flip the Active mode switch. A "Jarvis active" notification stays while it runs.
  - It speaks at most once per cooldown (30 min by default). It stays silent during quiet hours (11 pm–7 am), calls, Do Not Disturb, silent mode, and on the lock screen.
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
| pipelines | **release** (release-check → lint → build → release-publish) |
| setup | setup, env-check, keystore-gen, secrets-push |
| build | lint, build |
| phone | devices, phone-pair, phone-connect, install |
| release | release-check, release-publish |

A pipeline presses its steps in order and stops at the first failure. Each step keeps its own log and status.

**There is one way to make an APK: the `build` step** (`scripts/build.sh`). It is always signed with the release key. `release` and `install` use it, and so do the GitHub workflows below. So a copy from Obtainium and a copy installed from your Mac can update each other. Only the version name differs:

| Made by | Version name |
|---|---|
| `tilt:release 0.6.0` or the GitHub **Release** workflow | `v0.6.0` |
| `tilt:install`, `tilt:build` or the GitHub **Build** workflow | `v0.6.0-dev+<commit>` (latest release + the commit; `.dirty` if uncommitted changes) |

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

#### Without the Mac (from GitHub only)

Edit the code on github.com (or github.dev) and commit to `main`. Then go to **Actions → Release → Run workflow** in the GitHub website or app, optionally with a version. It runs the same scripts as `tilt:release` (release-check → lint → build → release-publish), signed with the same key, and publishes the release Obtainium reads. **Actions → Build** makes a dev APK without releasing; download the `jarvis-apk` artifact, a zip with the APK inside.

Both workflows are manual only; nothing runs on push. They need the signing key in the repo's secrets: run `mise run tilt:secrets-push` once from the Mac, and again if the key changes.

### Phone

```bash
mise run tilt:phone-pair <ip:port> <code>   # once: Wireless debugging -> Pair device with pairing code
mise run tilt:phone-connect                 # finds the paired phone on Wi-Fi
mise run tilt:install                       # signed dev build, installs over the Obtainium copy
```
