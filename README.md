# Jarvis

A blank Android app, distributed through GitHub Releases and installable/updatable with [Obtainium](https://obtainium.imranr.dev/).

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

Tools are managed by [mise](https://mise.jdx.dev/); env and secrets by [fnox](https://fnox.jdx.dev/); [Tilt](https://tilt.dev/) is the dashboard. Android builds need an Android SDK locally (`ANDROID_HOME`); CI has one.

```bash
mise install          # java, gradle, tilt, fnox, gh
mise tasks            # list every command
mise run build        # debug APK
mise run lint
mise run install      # adb install
mise run tilt:up      # dashboard; every resource just runs a mise task
```

### Environment (fnox)

- `fnox.toml` is committed and holds non-secret defaults.
- `fnox.local.toml` is gitignored and overrides it. Put real secrets here. For now everything is **plain text, not encrypted**.

### Releasing

One-time setup:

```bash
mise run keystore:gen   # creates .secrets/release.jks and writes fnox.local.toml
mise run secrets:push   # uploads signing secrets to GitHub Actions
```

Back up `.secrets/` and `fnox.local.toml`: if the key is lost, installed copies can no longer be updated.

Each release:

```bash
mise run release 0.2.0   # tags v0.2.0 and pushes; Actions builds, signs, and publishes the APK
```

The app's version name comes from the tag and its version code from the Actions run number, so every release is a valid upgrade.
