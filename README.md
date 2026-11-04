# Press: Workout Log

A workout log for Android. Kotlin + Jetpack Compose, offline-first. Data lives in one JSON file on the device.

## Network and Health Connect (context layer)

Press uses the network for exactly two things, both calls to Google's Gemini API: turning the "what happened" note into chips, and writing the coach summary. Nothing else goes online. Only the note and short derived facts are sent, never raw Health Connect records. Every AI feature falls back to a plain template when there's no key, no network or no valid reply.

Health Connect is read-only: the last 14 days of exercise sessions, steps, distance, elevation gained and (if recorded) sleep. Press never writes to it.

### Gemini key

Put the key and, optionally, the model IDs in `local.properties` (git-ignored). They go into every build type through `BuildConfig`:

    GEMINI_API_KEY=...
    GEMINI_MODEL=...            # optional; defaults in app/build.gradle.kts
    GEMINI_MODEL_FALLBACK=...   # used when the main model returns 429

Without a key the app builds and runs, and the AI features fall back cleanly.

**An APK built with a key has the key inside it.** Install those builds on your own phone only. Never publish one (GitHub releases included): build releases for sharing without `GEMINI_API_KEY` set.

The app was called Forge until the rename to Press. Internal names keep "forge" on purpose so updates and existing data keep working: the applicationId `app.forge.lift`, the Kotlin package `app.forge`, the data file `forge.json`, and the release keystore and alias below. Backups are now saved as `press-backup-YYYY-MM-DD.json`; older `forge-backup-*.json` files still import.

GitHub release names use the full name, e.g. "Press: Workout Log v1.2".

## Build

Needs JDK 17 and the Android SDK (platform 35). `local.properties` points at the SDK.

    ./gradlew assembleRelease      # -> app/build/outputs/apk/release/app-release.apk
    ./gradlew assembleDebug

Headless screenshots of every screen (no emulator needed):

    ./gradlew testDebugUnitTest -Proborazzi.test.record=true   # -> app/build/outputs/roborazzi/*.png

## Known issues

- `FinishToHomeTest` (2 tests: `showsTheShelfThenReturnsHomeOnItsOwn`, `anyTapSkipsStraightHome`) fails: the finish flow never reaches its "Done" button in the test. It already failed before the context layer (checked on commit `2cc866f`) and is left as it is for now.

## Research and testing

Two testers, each on their own phone. Changes made after phone testing:

- **Home was too busy.** The repair quest card, a separate Outside card and a separate Steps card competed with the templates. Now:
  - Header: the date line, then "Morning / Afternoon / Evening, Corbin" with the profile picture centred in the same row.
  - The streak sits in the "This week" card header: `0 / 5 SESSIONS · 🔥 1 WK · REPAIR 0/5`. Tapping the streak part opens the repair quest details (or Pause, without a quest).
  - One "Away from the gym" card: steps today against the daily target (Profile → Daily steps), whenever Health Connect has them. Below that, only when the watch has outings since the last confirmed note or dismissal, "2 hikes · 1 walk on your watch. Anything to add? ›", which opens "What happened?", with a × to dismiss. Without a connected watch the card is just the way into the note.
- **"What happened?" assumed hiking.** The placeholder is general now, and suggestion pills appear only when something recurs in your own confirmed notes.

## Next

- **Step target bonuses** (tester request, from the second tester): the Home step card and an editable daily target (Profile, default 10,000) are in; hitting or beating the target gives nothing yet. Next: XP for hitting the target.
- **Big days count toward the streak:** a day well over the step target counting like an outside session for the weekly goal.

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
- Context layer: `data/LifeContext.kt` (outings, note dates, check-in facts) and `data/Notes.kt` (note prompt, schema, validation), both pure; `HealthConnectReader.kt`; `Gemini.kt` (the only network code); `ui/screens/ContextScreens.kt`
- `ui/theme/Theme.kt`: design tokens (console layer from Figma, Press layer after Stripe Press)
- `ui/screens/`: Home, Workout, History, Metrics, Identity, plus overlays (picker, template editor, program, summary)
