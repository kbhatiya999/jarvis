# Jarvis

A voice assistant in the J.A.R.V.I.S. persona, built on the Gemini Live API. It's distributed through GitHub Releases and installable/updatable with [Obtainium](https://obtainium.imranr.dev/).

## Using it

- On first launch it asks for a Gemini API key. Get one at [Google AI Studio](https://aistudio.google.com/api-keys) and paste it in. The key is stored only on the phone, encrypted with the Android Keystore. Change it any time under **Settings → Change API key**.
- Tap **Talk** to speak, or type a message. Jarvis replies by voice (voice "Charon"), and both sides show up as text.
- The conversation is saved on the phone and fed back to Jarvis at the start of each session, so it remembers earlier chats. **Settings → Forget conversation** wipes it.
- **Active mode** (Settings → Active mode): when you open an app, Jarvis speaks up on its own. It reminds you about water, junk and oily food, staying warm, and not getting hurt, choosing and combining these itself. It then listens for 5 seconds and stops if you stay silent. Setup:
  1. Turn on "Jarvis active mode" under Accessibility. If it's greyed out, go to App info → ⋮ → Allow restricted settings first.
  2. Flip the Active mode switch. A "Jarvis active" notification stays while it runs.
  - It speaks at most once per cooldown (30 min by default). It stays silent during quiet hours (11 pm–7 am), calls, Do Not Disturb, silent mode, and on the lock screen.

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

Tools are managed by [mise](https://mise.jdx.dev/); env and secrets by [fnox](https://fnox.jdx.dev/); [Tilt](https://tilt.dev/) is the dashboard. mise also installs the Android SDK; `mise install` then `mise run setup` is all a fresh machine needs.

```bash
mise install          # java, gradle, android-sdk, tilt, fnox, gh
mise tasks            # list every command
mise run build        # debug APK
mise run lint
mise run install      # install on your phone (see Phone)
mise run tilt:up      # dashboard; every resource just runs a mise task
```

### Environment (fnox)

- `fnox.toml` is committed and holds non-secret defaults.
- `fnox.local.toml` is gitignored and overrides it. Put real secrets here. For now everything is **plain text, not encrypted**.

### Releasing

Nothing builds or releases automatically, not on save, commit or push. Releases are made from your Mac:

```bash
mise run release          # next patch version (see: mise run version:next)
mise run release 0.6.0    # or a version you choose
```

This lints, builds and signs the APK locally, tags `vX.Y.Z`, and publishes the GitHub release that Obtainium reads. From Tilt, use the **release** button (with an optional version box) or `mise run tilt:release [x.y.z]`.

One-time setup: `mise run keystore:gen` creates the signing key in `.secrets/` and `fnox.local.toml`. Back both up: if the key is lost, installed copies can no longer be updated.

Fallback: `mise run release:ci [x.y.z]` has GitHub Actions build and publish instead. It needs `mise run secrets:push` once. The Build and Release workflows only run when started by hand.

### Phone

```bash
mise run phone:pair <ip:port> <code>   # once, from Wireless debugging -> Pair device with pairing code
mise run phone:connect                 # finds the paired phone on Wi-Fi
mise run install                       # release-signed build, installs over the Obtainium copy
```
