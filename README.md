# Press: Workout Log

A workout log for Android. Kotlin + Jetpack Compose, fully offline. Data lives in one JSON file on the device.

The app was called Forge until the rename to Press. Internal names keep "forge" on purpose so updates and existing data keep working: the applicationId `app.forge.lift`, the Kotlin package `app.forge`, the data file `forge.json`, and the release keystore and alias below. Backups are now saved as `press-backup-YYYY-MM-DD.json`; older `forge-backup-*.json` files still import.

GitHub release names use the full name, e.g. "Press: Workout Log v1.2".

## Build

Needs JDK 17 and the Android SDK (platform 35). `local.properties` points at the SDK.

    ./gradlew assembleRelease      # -> app/build/outputs/apk/release/app-release.apk
    ./gradlew assembleDebug

Headless screenshots of every screen (no emulator needed):

    ./gradlew testDebugUnitTest -Proborazzi.test.record=true   # -> app/build/outputs/roborazzi/*.png

## Release signing

Release builds are signed with the key described in `keystore.properties` (project root, git-ignored). Without that file the build still works but falls back to the debug key and prints a warning.

1. Create the key once, outside the repo (keytool ships with the JDK):

        keytool -genkeypair -v -keystore C:\Users\magic\keys\forge-release.jks -alias forge -keyalg RSA -keysize 4096 -validity 10000

   It's a PKCS12 store, so use the same password for the store and the key.
2. Copy `keystore.properties.example` to `keystore.properties` and fill it in. `storeFile` is relative to the project root or absolute.
3. Back up the `.jks` file and both passwords somewhere other than this computer. Losing the key means every future build has to be installed fresh (uninstall first, which deletes the data on the phone).

Never commit the keystore or `keystore.properties`.

Switching an installed app from one key to another isn't possible in place: Android refuses an update signed by a different key. Export a backup (Profile → Export data), uninstall, install the newly signed build, then Profile → Import data.

## Layout

- `data/`: models, exercise library + programs, stats (volume, Epley e1RM, PRs, streaks)
- `AppViewModel.kt`: all state changes plus JSON persistence
- `RestAlarm.kt`: rest-over notification when the app is in the background
- `ui/theme/Theme.kt`: design tokens (console layer from Figma, Press layer after Stripe Press)
- `ui/screens/`: Home, Workout, History, Metrics, Identity, plus overlays (picker, template editor, program, summary)
