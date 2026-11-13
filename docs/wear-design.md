# Press for Wear OS: design

Design for `docs/advanced-plan.md` Phase 2 step 3 ("Watch app steps"). Plan only; no code yet.
Checked against developer.android.com (Health Services active data, Wearable Data Layer) in November 2026.

## 0. Corrections to the advanced plan

The plan's versions and Health Services details were written from memory. The current docs differ here:

- **Heart-rate permission.** On API 36+ (Wear OS 6, current Pixel Watch) heart rate needs
  `android.permission.health.READ_HEART_RATE` instead of `BODY_SENSORS`. Declare both
  (`BODY_SENSORS` with `maxSdkVersion="35"`) plus `ACTIVITY_RECOGNITION`. Health Services rejects
  `prepareExercise`/`startExercise` if the permissions aren't granted first.
- **Foreground service type** is `health` (plus `FOREGROUND_SERVICE_HEALTH`). No `location`: no GPS for lifting.
- **Versions:** `androidx.health:health-services-client:1.1.0`,
  `com.google.android.gms:play-services-wearable:20.0.1`.
- **Exercise type.** Check `getCapabilitiesAsync()` for `ExerciseType.STRENGTH_TRAINING`, fall back to
  `WORKOUT`. Request only data types listed in `supportedDataTypes` (`HEART_RATE_BPM`, `CALORIES_TOTAL`).
- **One exercise per device, across all apps.** Call `getCurrentExerciseInfoAsync()` first. If it says
  `OTHER_APP_IN_PROGRESS` (for example a Fitbit workout started by hand), ask before taking over.
- **`ExerciseUpdate` is the source of truth** for state, not the returned future. Handle `isEnded`
  (ended by another app, `AUTO_END_PERMISSION_LOST`): save what was recorded, remove the ongoing
  activity, notify.
- **Battery.** With the screen off, Health Services batches updates (up to about 150 s). Keep the default;
  don't use `BatchingMode` overrides. The rest timer is drawn from `restEndsAt` on the wall clock, never
  from update cadence. Data-point times are boot-relative; convert them with the boot instant.
- **Privacy.** When Bluetooth is down, the Data Layer can route through Google's servers (end-to-end
  encrypted). Only workout state crosses it. Add one line about this to the README, since Press is offline-first.
- **Availability.** Call `GoogleApiAvailability.checkApiAvailability(client)` before any Data Layer call.
  The phone must work unchanged without Play services.
- **Confirmed:** the two apps only talk if package (`app.forge.lift`) and signature match.

Prerequisite (Phase 3.2, one logging path): `data/Session.kt` already has `log`, `untick`, `setValues`,
`startRest`, `adjustRest`, `skipRest`, all pure and taking `now`. The watch path reuses them.

## 1. Module split

- **`:shared`**: pure Kotlin JVM module (`kotlin("jvm")` + kotlinx.serialization). Package stays
  `app.forge.data`, so imports and `forge.json` don't change. Holds `Models.kt`, `Session.kt`,
  `Measure.kt`, `Stats.kt` and whatever pure code they pull in (found by moving and compiling), plus the
  new `WatchProtocol.kt`. Their tests move with them.
- **`:app`** depends on `:shared`. `ForgeStore`, `Migrate` and everything Android stay in `:app`; the watch
  never reads `forge.json`.
- **`:wear`**: Android app. `applicationId app.forge.lift`, namespace `app.forge.wear`, minSdk 30,
  targetSdk 35, compileSdk 36, Compose for Wear OS (Material 3), signed from the same `keystore.properties`.
  Manifest: `uses-feature android.hardware.type.watch`, meta-data `com.google.android.wearable.standalone=false`.
- `play-services-wearable` and `kotlinx-coroutines-play-services` (for `await()`) in `:app` and `:wear`.
  This is the phone app's first Play services dependency.

## 2. Sync snapshot (phone → watch, `DataClient`)

- One DataItem at `/press/v1/session`, `PutDataMapRequest` with `setUrgent()`. Keys: `v` (Int, 1) and
  `json` (String, a serialized `WatchSnapshot`). Well under 2 KB (limit 100 KB).

```kotlin
@Serializable data class WatchSnapshot(
    val v: Int = 1, val seq: Long,
    val workoutId: Long, val name: String, val startedAt: Long,
    val exIndex: Int, val exCount: Int,
    val next: NextSet?,              // null = all sets done
    val restEndsAt: Long?,           // epoch ms; null = not resting
    val restTotalSec: Int, val unit: String,  // "kg" | "lb": display only, see below
)
@Serializable data class NextSet(
    val setId: Long, val exerciseName: String,
    val weight: Double, val reps: Int, val setNo: Int, val setCount: Int,
    val bodyweight: Boolean,
)
```

- **Weights on the wire are always kg**, in both directions (`NextSet.weight`, `WatchAction.weight`). `unit` says
  only what the user wants to see; the watch converts for display with `WatchProtocol.display(kg, unit)` and
  converts back to kg before sending. The phone never converts an incoming weight, so a unit change between a
  snapshot and an action can't change what gets logged. The next snapshot carries the new `unit`.
- Built by pure `WatchProtocol.snapshot(d: AppData): WatchSnapshot?` from `Session.next(d)`.
- No active session: `deleteDataItems` on the path. The watch treats a missing item as "session ended".
- `seq` only goes up; the watch ignores anything older than what it has.
- `WatchSync` runs in `ForgeRepository` init next to `RestNotifier.sync`, on `distinctUntilChanged` of the
  snapshot (on state change, not every second).
- Times are absolute epoch ms. Phone and watch clocks are network-synced; skew under a second is accepted.

## 3. Messages

| Path | Direction | Transport | Payload |
|---|---|---|---|
| `/press/v1/session` | phone → watch | DataItem | `WatchSnapshot` |
| `/press/v1/start` | phone → watch | Message | `workoutId` |
| `/press/v1/action` | watch → phone | Message | `WatchAction` |
| `/press/v1/summary/<workoutId>` | watch → phone | DataItem | `WatchSummary` |

- Nodes are found with `CapabilityClient`: the watch declares `press_wear` in `res/values/wear.xml`, the
  phone declares `press_phone`. Messages go only to nodes with the capability.

```kotlin
@Serializable data class WatchAction(
    val v: Int = 1, val actionId: String /* UUID */, val workoutId: Long, val setId: Long?,
    val type: Type, val weight: Double? = null, val reps: Int? = null, val sentAt: Long,
) { enum class Type { LOG, LOG_VALUES, ADD15, SKIP, FINISH } }

@Serializable data class WatchSummary(
    val v: Int = 1, val workoutId: Long, val startedAt: Long, val endedAt: Long,
    val avgHr: Int?, val maxHr: Int?, val calories: Double?,
    val hrStart: Long, val hrDeltas: List<Int>,  // at most one sample per 10 s; 1 h ≈ 360 values
    val endReason: String,                       // "finished" | "other_app" | "permission_lost" | ...
)
```

- The summary is a DataItem, not a message, so it survives a disconnect. The phone deletes it once stored.
- Pure `WatchProtocol.apply(d: AppData, a: WatchAction, now: Long): AppData` maps actions onto `Session`
  functions. It returns `d` unchanged for an unknown `v`, a different `workoutId`, or a set already logged
  (which is what makes a double tap safe). The phone also keeps the last 50 `actionId`s in memory to drop
  exact repeats.

## 4. Failure handling

| Case | Behaviour |
|---|---|
| No Play services, or no watch with `press_wear` | `WatchSync` does nothing; phone unchanged |
| Watch out of range mid-session | Watch keeps recording, shows the last snapshot greyed with "Phone away". Actions go into a small queue (DataStore) on the watch and are sent on reconnect; the phone's next snapshot then wins |
| `sendMessage` fails | Action stays queued; resent on reconnect. A repeat LOG is a no-op |
| Phone process killed | Phone `WearableListenerService` (path prefix `/press/v1/`) wakes it, loads the repository, applies, saves |
| Unknown `v` on either side | Ignored; watch shows "Update Press on both devices" |
| Another app starts an exercise, permission lost, battery saver ends it | Watch sends a partial summary, removes the ongoing activity, notifies. Phone session untouched |
| Another exercise already running at auto-start | Watch asks; never takes over silently |
| Phone ends the session while the watch is away | On reconnect the session item is gone: watch ends the exercise and sends its summary |
| Summary for an unknown or deleted workout | Deleted, not stored |
| Same summary twice | Keyed by `workoutId`; second is ignored |
| Health Connect | Phone stays the only writer. If the summary arrives after finish, it updates the existing record (`Workout.healthRecordId`) |

## 5. Build order

One item per session, one commit each, tests first where marked. Numbers in brackets are advanced-plan steps.

1. **W1 Shared module** [1]. Move the pure files into `:shared`. All existing tests pass; no behaviour change.
2. **W2 Protocol** [2, 6]. `WatchProtocol.kt` types, `snapshot()`, `apply()`. Tests first: `WatchProtocolTest`
   (snapshot from fixtures, log twice logs once, stale set, wrong workout, bad version, +15 and Skip).
3. **W3 Wear skeleton** [1, 10]. `:wear` builds and signs with the same key; one screen; adb install steps in README.
4. **W4 Phone WatchSync** [2]. Put/delete the snapshot, availability check, capability lookup. Robolectric test:
   no-op without Play services.
5. **W5 Read-only rest screen** [5a]. Timer states, next set, buzz at zero. Useful on its own.
6. **W6 Wrist actions** [6]. Log, +15, Skip as messages; phone listener service → `WatchProtocol.apply`.
7. **W7 Auto-start** [3]. Start message; watch listener opens the workout screen.
8. **W8 Recording** [4]. Foreground `health` service, `ExerciseClient`, ongoing activity, permissions
   including `READ_HEART_RATE` on API 36, other-app check.
9. **W9 Finish and summary** [7]. Summary DataItem; `Workout.avgHr/maxHr/calories` (Migrate step and test);
   Health Connect record with heart rate; shown on the session summary and in History.
10. **W10 Steppers and stats screens** [5b, 5c]. Crown steppers, LOG_VALUES, heart rate, time, Finish.
11. **W11 Offline queue** [8]. Watch action queue and flush on reconnect; queue logic pure and tested.
12. **W12 Settings** [9]. Profile → "Start on my watch" and the "watch app installed" line.
13. **W13 Real watch** [10]. One-hour session on the Pixel Watch; battery use noted in `AI_LOG.md`.

## 6. Open questions

- Does Fitbit show heart rate from a session Press wrote to Health Connect? (Carried over from step 1.)
- Does the Pixel Watch support `STRENGTH_TRAINING` with heart rate and calories? Checked at runtime in W8.
