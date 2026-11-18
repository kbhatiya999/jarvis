# Notes for coding agents

## iOS is on hold until Android succeeds (decided 2026-11-18)

- **Android only.** Build, change and test features in the Android app (`app/`).
- **Shared core still targets iOS.** `shared/` stays Kotlin Multiplatform: shared logic goes in `commonMain`
  without Android-only APIs, and the iOS targets must keep compiling. Don't break or drop them.
- **Don't touch the iOS app.** No new features, screens or fixes in `ios/` (Swift, `project.yml`, iOS build
  scripts). New shared features don't need iOS UI or wiring.
- **No iOS checks in routine work.** Don't run `tilt:ios-build` or `tilt:ios-sim` as part of checking a change.
  The exception is a change to `shared/` that could break the iOS targets: then make sure they still compile.
- **Lifting the hold** is the user's decision, once the Android app is successful. Until then, ask before doing
  any iOS work.
