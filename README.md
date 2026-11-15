# Jarvis

A voice assistant in the J.A.R.V.I.S. persona, built on the Gemini Live API. It's distributed through GitHub Releases and installable/updatable with [Obtainium](https://obtainium.imranr.dev/).

## Using it

- On first launch it asks for a Gemini API key. Get one at [Google AI Studio](https://aistudio.google.com/api-keys) and paste it in. The key is stored only on the phone, encrypted with the Android Keystore. Change it any time under **Settings → Change API key**.
- Tap **Talk** to speak, or type a message. Jarvis replies by voice (voice "Charon"), and both sides show up as text.
- The conversation is saved on the phone and fed back to Jarvis at the start of each session, so it remembers earlier chats. **Settings → Forget conversation** wipes it.
- **Active mode** (Settings → Active mode): when you open an app, Jarvis speaks up on its own. It works through your reminders, choosing and combining them itself, and passes on any messages from other Jarvises. It then listens for 5 seconds and stops if you stay silent. Setup:
  1. Turn on "Jarvis active mode" under Accessibility. If it's greyed out, go to App info → ⋮ → Allow restricted settings first.
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
mise install                 # java, gradle, android-sdk, tilt, fnox, gh
mise tasks                   # the tilt:* commands
mise run tilt:status         # every step and pipeline, with its last result
mise run tilt:logs [name]
```

In Tilt, resources are grouped into:

| Group | Resources |
|---|---|
| pipelines | **release** (release-check → lint → build-release → release-publish), **install** (build-release → install-apk) |
| setup | setup, env-check, keystore-gen, secrets-push |
| build | lint, build, build-release |
| phone | devices, phone-pair, phone-connect, install-apk |
| release | release-check, release-publish, release-ci |

A pipeline presses its steps in order and stops at the first failure. Each step keeps its own log and status.

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

Fallback: `mise run tilt:release-ci [x.y.z]` has GitHub Actions build and publish instead. It needs `mise run tilt:secrets-push` once. The GitHub workflows only run when started like this or by hand.

### Phone

```bash
mise run tilt:phone-pair <ip:port> <code>   # once: Wireless debugging -> Pair device with pairing code
mise run tilt:phone-connect                 # finds the paired phone on Wi-Fi
mise run tilt:install                       # signed build, installs over the Obtainium copy
```
