# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Press: Workout Log. An offline-first Android app (Kotlin + Jetpack Compose); all data lives in one JSON file on the device. It was called Forge before the rename. Internal names keep "forge" on purpose so updates and existing data keep working: applicationId `app.forge.lift`, package `app.forge`, data file `forge.json`, and the release keystore and alias. Do not rename them. Backups are `press-backup-YYYY-MM-DD.json`; older `forge-backup-*.json` still import.

## Commands

Needs JDK 17 and the Android SDK (platform 35); `local.properties` points at the SDK. Run through `./gradlew`.

    ./gradlew testDebugUnitTest                                  # all unit tests (JVM; Robolectric + Roborazzi, no emulator)
    ./gradlew testDebugUnitTest --tests "app.forge.StreaksTest"  # one suite (add .methodName for one test)
    ./gradlew testDebugUnitTest -Proborazzi.test.record=true     # also writes screenshots to app/build/outputs/roborazzi/*.png
    ./gradlew assembleDebug
    ./gradlew assembleRelease                                    # -> app/build/outputs/apk/release/app-release.apk

There is no lint task or CI configured.

Known failing tests: two in `FinishToHomeTest` (`showsTheShelfThenReturnsHomeOnItsOwn`, `anyTapSkipsStraightHome`). They failed before the context layer was added; leave them unless asked.

Test conventions seen in the history:
- Don't put `;` in test names.
- UI tests tap Skip on the check-in sheet (`skipCheckIn()` in `TestCheckIn.kt`) and Ignore on the tweaks banner, as a user would, and never call Gemini.
- `RealDataTest` and `RealCoachTest` read the git-ignored `app/src/test/resources/private/` and skip cleanly without it. Committed fixtures stay synthetic.

## Architecture

- `data/` holds pure Kotlin (no Android): models, exercise library and programs, stats (volume, Epley e1RM, PRs), streaks, progression, readiness, coach validation, `Session.kt`. Keep it pure so it stays JVM-testable.
- `AppViewModel.kt` holds UI state changes plus JSON persistence. `ForgeRepository` and `data/ForgeStore.kt` read and write `forge.json`. `data/Migrate.kt` handles older files.
- Live session logic is split across surfaces: the app uses the view model, while the rest notification (`RestAlarm.kt`) and lock-screen editor (`LockEditActivity`) go through `Session`. A change to how a set is logged or a rest ends usually touches both paths. `docs/advanced-plan.md` tracks merging them (Phase 3.2 and 3.3).
- Context layer (outside activity, check-in, AI notes):
  - `HealthConnectReader.kt` reads Health Connect. It is read-only and covers the last 14 days. Derived per-day totals are stored in `forge.json`, never raw records.
  - `data/LifeContext.kt`, `data/Outside.kt` and `data/Notes.kt` are pure: outings, note schema and validation.
  - `data/Readiness.kt` gates the existing `Progression.kt` engine: green is as before, amber never increases, red cuts. The progression engine remains the source of truth.
  - `Gemini.kt` is the only network code. It is used for exactly two calls, the note-to-chips parse and the coach summary. Kotlin validates every reply (allowed actions, known fact IDs, word limits, every number must come from computed facts) and otherwise falls back to a template. Any 429, 5xx or timeout retries on the fallback model.
- `SundayIssue.kt` and `data/Issues.kt` build the weekly issue synchronously; the AI headline is cached per week, so back issues keep the template.
- Streak is a weekly-goal streak with freezes, comeback weeks and a repair quest, not "one session a day". Outside sessions can count toward the goal (see `data/Streaks.kt`). Change it carefully.
- `ui/theme/Theme.kt` holds the design tokens (console layer from Figma, Press layer after Stripe Press). Lime means success. `ui/screens/` has one file per area, and overlays must call `blockTaps()` so touches don't fall through to the screen underneath.
- Gotcha: a watch session that mostly overlaps a logged workout is that workout. It is dropped before merging so the gym session isn't counted twice.

## Config and secrets

- `local.properties` (git-ignored) can set `GEMINI_API_KEY`, `GEMINI_MODEL` and `GEMINI_MODEL_FALLBACK`. They go into `BuildConfig` for every build type. Without a key the app builds and the AI features use templates.
- An APK built with a key contains the key. Build anything meant for sharing, including GitHub releases, without `GEMINI_API_KEY`.
- Release signing uses `keystore.properties` (git-ignored, see `keystore.properties.example`). Without it the build falls back to the debug key, and Android will refuse to update an app installed with a different key. Never commit the `.jks` or `keystore.properties`.
- Health Connect is pinned at `1.1.0-beta01`; 1.1.0 needs compileSdk 36 and AGP 8.9.1+. Don't bump it alone.

## Working notes

- `docs/advanced-plan.md` is the approved roadmap, built one item at a time. Don't start an item that wasn't asked for. Phase 3.1 (corrupt-file safety, weekly auto backup) and Phase 5.1 (automatic backup) are done.
- `AI_LOG.md` is a running log of build sessions. README "Next" lists the open feature requests.
- `docs/Design/` is git-ignored (the Figma file is linked in the README).
