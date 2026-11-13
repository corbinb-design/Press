# Press: advanced plan

## Context

The APK is in good shape after two rounds of phone testing. This plan covers the next level: the fixes
from testing, getting sessions onto the Pixel Watch / health app, and what a read-through of the code
and design turned up. Approved on 13 Nov 2026. Nothing in it has been built yet; each phase is its own
piece of work.

How this was reviewed: by reading the source (session, rest timer, notification, Health Connect, theme,
components, Home and Workout screens) and counting token usage across `ui/`. The app was not run on a
device, and History, Metrics, Profile and the issue screens were only sampled, so the design findings
for those are counts rather than screen-by-screen notes.

---

## Phase 1: fixes from testing

### 1.1 Rest timer: three states, and it runs past zero

Decided with you:

| State | Look | Timer |
|---|---|---|
| Working (no rest running) | today's ink card | session clock, counting up |
| Resting | a card that stands out: lime card, ink text (proposed) | counting down |
| Over | today's ink card, lime timer | keeps counting, negative: `−0:12` |

At zero: one buzz, the notification changes in place, no separate "Rest's up" pop-up. The rest stays
until the next set is logged or Skip is tapped.

How:
- Today a rest ends by being cleared (`restEndsAt = null`) in two places: `ForgeApp.kt:110-117`
  (foreground) and `RestActionReceiver.REST_END` in `RestAlarm.kt:229-235` (background). Both stop
  clearing it. "Resting" becomes `restEndsAt != null`; "over" is `now >= restEndsAt`.
- `Session.adjustRest` (`data/Session.kt:111`) currently ends the rest when −15 takes it past now;
  it should just move the end time.
- Notification (`RestNotifier.ongoing`): the countdown `Chronometer` already shows negative time by
  itself once past its base, so no ticking code is needed. The background swaps with
  `setInt(R.id.root, "setBackgroundResource", …)`; add `notif_bg_rest.xml` and a root id on both layouts.
  The end-of-rest alarm stays, but now only buzzes and re-posts the same notification.
- The `restUp` heads-up and `notif_rest_up.xml` go. Their Log and Edit actions move onto the ongoing
  notification (Log is already there; add Edit, which opens `LockEditActivity`).
- In app: `RestPill` (`WorkoutScreen.kt:753`) gets the same three states.
- Tests to update: `RestNotificationTest`, `LockEditTest`.

### 1.2 Rest timer visible in the set editor, and no longer clipped

- Set editor: show the rest countdown in the header of `SetStepperSheet` (`WorkoutScreen.kt:796`) and
  of `LockEditScreen`. Pull the pill's clock out into one small `RestClock` composable used by all three.
- Clipping, likely cause: in `notif_rest.xml` the timer has `layout_weight=1` and the right-hand column
  (set label, values, exercise name) is `wrap_content` with no limit, so a long exercise name squeezes
  the 40sp timer. Flip it: timer `wrap_content`, right column weighted with ellipsis. The collapsed
  layout (`notif_rest_small.xml`) has a 24sp timer plus its own card inside the system's ~48dp row;
  drop the inner padding and size. Negative time is one character wider, so check `−10:00`.
  To confirm on the phone with a long exercise name and large font size before and after.

### 1.3 Save weight and reps without completing the set

- `SetStepperSheet` gets a second button under "Log set · start rest": "Save for later". It calls
  `vm.updateValues` (`AppViewModel.kt:383`), which already sets values without ticking. No rest starts.
- Same button on `LockEditScreen`, through a new pure `Session.setValues` so the lock screen and the
  app share it.
- The row should show saved values differently from last session's ghost values (ghost = muted,
  saved = bone), so it's clear what was typed. `SetRow` colours are in `Components.kt` (`valueColor`).

### 1.4 Spacing tightened

Needs your eye on which elements; what the code shows:
- 17 different gap sizes are in use (2, 3, 4, 6, 8, 10, 12, 14, 16, 18, 20, 22, 24, 25, 26, 28, 32 dp).
  `Theme.kt` has tokens for gutter and section gap only.
- Proposal: a six-step scale in `Forge` (4 / 8 / 12 / 16 / 24 / 32) and one rule: 4–8 inside a group,
  12 between rows in a card, 16 between cards, 24–32 between sections. Then a pass per screen, starting
  with Workout and Home, using Roborazzi screenshots before and after.
- Ask: mark up two or three screenshots with the pairs that feel too far apart, so the pass starts there.

### 1.5 Inner and outer thigh as their own muscles

- Today Hip Abduction, Hip Adduction and Cable Hip Abduction are all filed under "Glutes"
  (`data/LibraryData.kt:210, 219, 220`), and the muscle list (`data/Library.kt:6`) has no inner or
  outer thigh. So the picker can't filter them apart and Balance counts adduction as glute work.
- Add two muscles, "Adductors" (inner thigh) and "Abductors" (outer thigh), keeping "inner thigh" and
  "outer thigh" as search aliases. Move the three exercises; give abduction "Glutes" as a secondary
  muscle so it still counts half a set there. Add Copenhagen Plank's secondary as Adductors.
- Add the missing variants: cable hip adduction, banded abduction, side-lying leg raise, and
  seated / standing machine versions if you use both.
- Old sessions store only the exercise id, so history, Balance and records follow the new grouping
  with no migration. To check: Balance (`MetricsScreen.kt`) layout with two more rows, the muscle
  filter chips in the picker, and `LibraryTest`.
- The same gap may exist elsewhere (e.g. front / side / rear delts are all "Shoulders"). Worth a
  decision on how fine the groups should go before adding more.

### 1.6 "BW" shown for bodyweight exercises

- Today "BW" appears only when the weight is 0 on bodyweight-reps and assisted sets
  (`data/Measure.kt:105-106`). For a dead hang (timed, bodyweight) the editor shows "ADDED WEIGHT" with
  a plain `0 kg`, and nothing says that leaving it at 0 means bodyweight.
- Set editor (`SetStepperSheet`, and `LockEditScreen`): for bodyweight-reps and bodyweight holds, the
  weight field reads **BW** at 0 and **BW + 10 kg** above it, labelled "ADDED WEIGHT · OPTIONAL". The
  minus button stops at BW.
- Set row and "Previous" column: show "BW" in the weight cell, never an empty cell or 0, for the same
  types (`Measure.cells`, `Measure.format`).
- Timed bodyweight holds (dead hang, plank): lead with the time; the weight row collapses to a
  "BW · add weight" chip that opens the stepper, since most people never add weight to these.
- Tests: `TrackingTest`, `StepperTest`.

---

## Phase 2: Pixel Watch and the health app

Fitbit on the Pixel Watch can't be started by another app, so "starts on the watch automatically" needs
Press's own watch app. Agreed order:

1. **Save finished sessions to Health Connect** (small). On finish, write one `ExerciseSessionRecord`
   (strength training, session name as title, start and end times), with one segment per exercise if
   the API accepts them cleanly. New permission `WRITE_EXERCISE`, asked the first time, with a Profile
   toggle. Press already ignores strength sessions when reading (`HealthConnectReader.typeOf`), so it
   won't count its own. Edits and deletes in History should update or remove the record (store the
   record id on `Workout`).
   - **Done.** Profile → "Save sessions to Health Connect" (off by default; turning it on asks for
     `WRITE_EXERCISE`). `Workout.healthRecordId` holds the record id; deleting the session removes the
     record. No segments (sets have no timestamps, so they'd be invented) and cardio-only sessions are
     not written. History has no session editing yet, so there is no update path. Sessions finished
     with the toggle off, or before it, are never back-filled.
   - Open question to test on your phone: whether Fitbit and the watch show a session another app wrote
     to Health Connect. I don't know this for certain.
   - If you also start a workout on the watch by hand, there will be two entries. Document it.
2. **Wrist actions without a watch app** (small). The ongoing notification is custom views only, with no
   title, text or standard actions, so its mirror on the watch has little to show. Add
   `setContentTitle/Text` ("Rest 1:30 · Squat 70 kg × 8") and standard actions (Log set, +15, Skip).
   Phone look is unchanged.
3. **Press for Wear OS** (large, later). Starts with the phone, shows rest timer and next set, logs a
   set from the wrist, records heart rate and calories. Steps below.

### Watch app steps

Before starting: Phase 3.2 ("one logging path") must be done, because the watch is the fourth place a
set can be logged from. Library versions and the Health Services details below are from memory and
need checking against the current docs when the work starts.

1. **Project setup.** Split the pure code both apps need (`data/Models.kt`, `Session.kt`, `Measure.kt`,
   `Stats.kt` and what they pull in) into a `shared` Kotlin module. Add a `wear` module: same
   `applicationId` (`app.forge.lift`) and same signing key as the phone app (the two only talk to each
   other if both match), Compose for Wear OS, minSdk 30. Add `play-services-wearable` to both apps;
   this is the phone app's first Google Play services dependency.
2. **Phone → watch state.** A small `WatchSync` called from the same place as `RestNotifier.sync`
   (`ForgeRepository` init). It sends a compact snapshot over the Data Layer (`DataClient`): session id
   and name, exercise n of m, next set (name, weight, reps, set x of y), `restEndsAt`, `restTotalSec`,
   started-at. Cleared when the session ends. The phone stays the only owner of the data.
3. **Auto-start.** When a session starts, the phone sends a "start" message (`MessageClient`) to the
   connected watch. A `WearableListenerService` in the watch app receives it and starts the workout
   service and screen. If the watch isn't reachable, nothing happens and the phone carries on as today.
4. **Recording on the watch.** A foreground service using Health Services `ExerciseClient`: strength
   training type, heart rate and calories. An ongoing activity so the session shows on the watch face
   and survives the screen turning off. Permissions asked on the watch the first time: body sensors
   and activity recognition.
5. **Watch screens.** Three, swipeable: (a) rest timer with the same three states as 1.1, next set
   under it, a large Log button, +15 and Skip; (b) weight and reps steppers for the next set, turned
   with the crown; (c) heart rate, session time, Finish. A buzz on the wrist at zero.
6. **Watch → phone actions.** Log, Log with values, +15, Skip and Finish go back as messages carrying
   the workout id and set id. A phone-side `WearableListenerService` runs them through the same
   `Session` functions the notification uses, so a double tap can't log twice (they are already
   idempotent). The new state then flows back by step 2.
7. **Finish.** The watch ends the exercise and sends the summary (average and max heart rate, calories,
   heart-rate samples) to the phone. The phone stores average heart rate on the `Workout` and writes the
   one Health Connect record from Phase 2 step 1, now with heart rate and calories. One writer, so no
   duplicate entries. Show heart rate on the session summary and in History.
8. **When things go wrong.** Watch out of range mid-session: it keeps recording and shows the last
   known set; actions queue and are sent on reconnect. Phone process killed: watch actions wake it
   through the listener service. Watch battery saver or the user ending it on the watch: the phone
   session is unaffected.
9. **Settings.** Profile → "Start on my watch" toggle (on by default once the watch app is detected),
   and a line showing whether the watch app is installed.
10. **Install and test.** Not on Play, so each tester turns on developer options and wireless debugging
    on the watch and installs the wear APK with adb; write the steps in the README. Tests: unit tests
    for the snapshot and the action handling in `shared`; the Wear emulator for screens; then a real
    session on the Pixel Watch, checking battery use over an hour.

Suggested build order: 1 → 2 → 5a (a read-only timer on the wrist is useful by itself) → 6 → 3 → 4 →
7 → 5b/5c → 8 → 9 → 10.

---

## Phase 3: code base

In priority order.

1. **A file that fails to read is replaced by an empty app.** `ForgeStore.read()` returns null on any
   error, `ForgeRepository.load()` (`:67-69`) then starts from defaults, and the next save overwrites
   `forge.json`. One bad write or one incompatible field loses all history. Fix: on a failed read, move
   the file to `forge.corrupt-<time>.json`, keep a rolling `forge.prev.json` on each successful load,
   and tell the user. Add an automatic weekly export to a folder the user picks.
2. **Two ways to log a set.** The app uses `AppViewModel.toggleDone / logValues` (`:342-377`); the
   notification and lock screen use `Session.logSet / logSetAs / tick`. The "bodyweight logs as 0" rule
   is written twice (`AppViewModel.kt:367`, `Session.kt:40`). Move the view model's versions into
   `Session` so every surface shares one path. Do this with 1.1 and 1.3, which touch the same code.
3. **One owner for the end of a rest.** Foreground and background are coordinated through
   `ForgeActivity.inForeground` and 2–3 s windows. 1.1 removes most of this; finish the job there.
4. **Split the two largest files.** `WorkoutScreen.kt` (1,060 lines) into rest pill, set editor,
   upcoming list and target card; `AppViewModel.kt` (663 lines) by area. 41 composables take the whole
   view model, which makes them hard to preview or screenshot alone; pass data and callbacks to leaf
   components first.
5. **Once-a-second recomposition.** `WorkoutScreen` reads the ticking clock at the top, so the header
   and progress block (set count, volume) are recalculated every second. Keep the clock inside the two
   small composables that show it. `UpcomingList` (`:519`) scans all history per tile without `remember`.
6. **Tests and CI.** 38 test files, no CI. Two tests in `FinishToHomeTest` are known to fail (README).
   Fix or delete them, then add a GitHub Actions run: unit tests, Roborazzi compare, release build
   without a Gemini key.
7. **Tooling update.** Compose BOM 2024.12, AGP 8.7.3, Kotlin 2.1.0, compileSdk 35, Health Connect
   1.1.0-beta01 (the build file already notes 1.1.0 needs compileSdk 36). One upgrade pass. If Play is
   ever the goal, check its current target-API requirement first.
8. **Gemini key.** It is baked into the APK, so AI features can't go to other testers. Either a key
   field in Profile or a small proxy. Needed before sharing AI builds more widely.
9. Small: `toggleDone` has an unused parameter (`:342`); `newId()` uses an unsynchronised counter that
   the receiver thread also touches; every user-facing string is inline (no `strings.xml`), fine until
   a second language is wanted.

---

## Phase 4: design consistency

- **Type:** 17 named styles in `Type`, 107 inline `fontSize` overrides on top (Workout 15, Metrics 13,
  Components 13, Issues 12). The same few recur: `DataM` at 12 and 13sp, `TitleM` at 14 and 15sp. Add
  those as named styles and replace the copies.
- **Corners:** tokens are 6 / 8 / 10 / 12; literals of 14 and 16 appear (PR toast, target card).
  Add `RSheet = 16` or bring them to 12.
- **Lime:** `Theme.kt` says lime means success only. It is also the stepper value border and number,
  "Add set", "Add exercise", the date line and the volume label. Decide: widen the rule to "success and
  the one primary action", and move the rest to bone or ash.
- **Theme wrapper:** `LockEditScreen` uses a bare `MaterialTheme {}` (default light scheme); the app's
  scheme is private in `ForgeApp.kt`. Extract a `ForgeTheme` and use it in every activity.
- **Notifications:** layouts repeat hex colours and use the system serif and monospace, not Instrument
  Serif / DM Mono. Point them at `@color` tokens; try `@font` resources (may not apply in system UI).
- **System toasts:** 5 `Toast.makeText` calls sit outside the app's look. Replace with one in-app
  message component.
- **Touch targets:** `IconTap` is 40dp; steppers are 48–72dp. Raise `IconTap` to 48dp.

---

## Phase 5: next-level features (pick, don't do all)

Recommended, in order:
1. **Automatic backup** (with Phase 3.1). All data is one file on one phone.
2. **Home screen widget and app shortcut:** "Start Legs" (the next-up template) in one tap.
3. **Supersets:** pair two exercises; rest starts after the second. Not in the app today.
4. **Per-exercise pinned note** ("seat 4, narrow grip") shown on the exercise page each session.
5. **Plate calculator and warm-up sets** on the set editor for barbell lifts.
6. **Step target XP and big days toward the streak** (already in README "Next").
7. **Import from Strong / Hevy CSV**, so a new tester arrives with history.
8. **Android 16 Live Updates** for the rest timer (status-bar chip, lock-screen prominence). Needs
   compileSdk 36 and gives up the custom card look, so weigh after 1.1 is on the phone.

---

## Suggested order

1. Phase 3.1 (data safety). Small, and the only item that can lose data.
2. Phase 1 with Phase 3.2 and 3.3 (same code).
3. Phase 2 step 1 and 2 (Health Connect write, wrist actions).
4. Phase 4 and 1.4 together (tokens first, then the spacing pass).
5. Phase 3.4–3.7, then Phase 5 picks, then the Wear OS app.

## Checking each phase

- `./gradlew testDebugUnitTest` (existing 38 suites, plus new tests for overtime, save-without-logging,
  the corrupt-file path, and the Health Connect record mapping).
- `./gradlew testDebugUnitTest -Proborazzi.test.record=true` for before/after screenshots.
- On the phone: a full session from the lock screen (rest → zero → negative → log), a long exercise
  name at large font size for the clipping, and a finished session appearing in Health Connect.
