# Rest timer design (Phase 1.1 with 3.2 and 3.3)

Scope: the three rest states from 1.1, one owner for the end of a rest (3.3), and one logging path in
`Session` (3.2). 1.2 (clock in the set editor, clipping) shipped separately in `9a27e69`. 1.3 (save without
logging) is not built here, but the `Session` API leaves room for it.

## 1. What happens today

- Rest is one field pair on the active `Workout`: `restEndsAt` (wall-clock ms or null) and
  `restTotalSec` (`data/Models.kt:110`).
- At zero the rest is **cleared** (`restEndsAt = null`), by two owners:
  - foreground: `ForgeApp.kt:110-117`, a `LaunchedEffect` that sleeps until the end, buzzes, calls `vm.skipRest()`;
  - background: `RestActionReceiver.REST_END` (`RestAlarm.kt:229-235`), which returns early when
    `ForgeActivity.inForeground`, accepts an end up to 3 s early, clears the rest and posts the "Rest's up" heads-up.
- `Session.adjustRest` (`data/Session.kt:111`) clears the rest when −15 takes it past now.
- Logging a set has two implementations:
  - app: `AppViewModel.toggleDone / logValues / updateValues` (`AppViewModel.kt:344-393`), by exercise and set
    **index**, read-then-write (`repo.value` read, then a separate `editEntry`), rest started by `restAfter` (skips cardio);
  - notification and lock screen: `Session.logSet / logSetAs / tick`, by **set id**, in one `repo.update`,
    idempotent, rest started inside `tick` (no cardio check; `next()` happens to skip cardio).
  - The bodyweight-logs-as-0 rule is written twice (`AppViewModel.kt:369`, `Session.kt:40`).
- All surfaces run in one process (no `android:process` in the manifest) and share the `ForgeRepository`
  singleton, whose `update` is atomic (`updateAndGet`, retried). Every change to `active` or `settings`
  calls `RestNotifier.sync`, which schedules or cancels the alarm and re-posts the notification.

## 2. The three states

Derived, never stored. One pure function is the only definition, used by every surface:

```kotlin
enum class RestPhase { WORKING, RESTING, OVER }
fun Session.rest(w: Workout, now: Long): RestPhase =
    when { w.restEndsAt == null -> WORKING; now < w.restEndsAt -> RESTING; else -> OVER }
// plus signedSecondsLeft(w, now): positive while resting, negative when over
```

| State | Stored data | App card | Notification | Timer |
|---|---|---|---|---|
| Working | `restEndsAt == null` | ink card | ink background | session clock, counting up |
| Resting | `restEndsAt > now` | lime card, ink text | lime background (`notif_bg_rest.xml`), progress bar | counting down |
| Over | `restEndsAt <= now` | ink card, lime timer | ink background, lime timer, no progress bar | counting on, negative (`−0:12`) |

The key change: **reaching zero writes nothing.** Resting becomes Over by the clock alone, so there is no
longer a race at zero between the app and the receiver. That is how 3.3 is closed.

### Transitions

| # | From | Event | To | Data change (all via `Session`) |
|---|---|---|---|---|
| T1 | Working | set logged (any surface), auto-rest on, not cardio | Resting | `restEndsAt = now + restFor(exercise)` |
| T2 | Working | set logged, auto-rest off or cardio | Working | none |
| T3 | Working | tap the rest card in the app ("tap to rest") | Resting | `startRest(default)` |
| T4 | Resting | clock reaches `restEndsAt` | Over | **none**; side effects only: one buzz, notification re-rendered |
| T5 | Resting | +15 | Resting | end +15 s, total +15 |
| T6 | Resting | −15, still in the future | Resting | end −15 s |
| T7 | Resting | −15 past now | Over | end −15 s (no longer clears; −15 is then hidden, D3) |
| T8 | Resting | set logged, auto-rest on | Resting (new rest) | end and total replaced by the new set's rest |
| T9 | Resting | set logged, auto-rest off or cardio | Working | `restEndsAt = null` (today it stays: a bug once Over exists) |
| T10 | Resting / Over | Skip | Working | `restEndsAt = null` |
| T11 | Over | set logged, auto-rest on | Resting | new rest, as T8 |
| T12 | Over | set logged, auto-rest off or cardio | Working | `restEndsAt = null` |
| T13 | Over | +15 | Resting | `restEndsAt = now + 15 s` (D2) |
| T14 | any | workout finished or cancelled | (no session) | `active = null`; alarm cancelled, notification removed by `sync` |
| — | any | set unticked, set skipped, exercise moved or removed | unchanged | none (decision D6) |
| — | any | a repeated or stale log (set already done, other workout) | unchanged | none (idempotent) |

Rule that falls out of T8/T9/T11/T12: **logging a set always ends the current rest; it starts a new one
only when auto-rest is on and the exercise isn't cardio.** Written once, in `Session.log`.

## 3. Who owns what

| Owner | Writes rest data? | Does |
|---|---|---|
| `Session` (pure) | It is the only code that computes new rest values | `log`, `untick`, `setValues`, `startRest`, `adjustRest`, `skipRest`, `rest()` |
| `ForgeRepository` | Applies `Session` results atomically | On any change to `active`/`settings`: `RestNotifier.sync` (the only scheduler of the alarm), debounced save |
| App (`AppViewModel`, `RestPill`, `ForgeApp`) | Through `Session` only | Renders the phase from `rest(w, now)` with its ticking clock. At zero, while the activity is at least STARTED, calls `RestAlarm.buzzOnce(ends)`. No longer calls `skipRest` at zero |
| Alarm (`RestAlarm` + `REST_END` in `RestActionReceiver`) | **No** | At `restEndsAt`: if the active rest's end is within the window, `buzzOnce(ends)` then `sync` so the notification flips to Over. No `inForeground` check |
| Notification actions (Log, +15, Skip, new Edit) | Through `Session` only | `repo.update`, `flush()`, `sync`, as today. Edit opens `LockEditActivity` |
| Ongoing notification (`RestNotifier.ongoing`) | No | Renders the phase: background, kicker, chronometer direction, progress, which buttons show. The `Chronometer` counts past zero by itself, so Over looks right even if the alarm never fires |
| Lock-screen editor (`LockEditActivity`) | Through `Session` only | Logs with chosen values via `Session.log`, flushes, syncs, closes |

**One buzz per rest.** `RestAlarm.buzzOnce(ends)` keeps the last buzzed `restEndsAt` in an `AtomicLong` and
buzzes only on a successful compare-and-set. Both the app's effect (exact, while visible) and the alarm
(within ~3 s, always) call it; whichever is first buzzes, the other only refreshes. It is in memory
only, which is fine because both run in the same process, and after process death only the alarm is left.
`ForgeActivity.inForeground` then has no users left in rest code.

**The buzz without the heads-up (risk).** Today the background buzz comes from the high-importance
"Rest's up" channel. A plain `Vibrator.vibrate` from a background receiver may be ignored on recent
Android. Two candidates, to settle on the phone first (build step 1, decision D1):
(a) vibrate with alarm usage attributes (`VibrationAttributes.USAGE_ALARM` on 33+, `AudioAttributes` usage alarm below);
(b) at zero, re-post the ongoing notification once on a new `IMPORTANCE_DEFAULT` channel with the
buzz pattern, no sound and no heads-up, then back on the quiet channel.

## 4. When things go wrong

**App killed mid-rest (swiped away, or killed for memory).**
- `restEndsAt` is a wall-clock time saved in `forge.json`. Notification actions and the lock screen
  `flush()` before returning, and the app flushes in `onStop`. The only loss window is the existing 300 ms
  debounce for an in-app change made just before a kill in the foreground. That isn't new and isn't in scope.
- The alarm and the posted notification belong to the system and outlive the process. The notification's
  chronometer keeps counting, through zero and negative, with no process alive.
- At the end time the alarm cold-starts the process. The receiver loads from disk, finds the rest ended,
  buzzes and re-posts the notification as Over. No data is written, so there's nothing to lose if it dies again.
- Reopening the app shows Over with the true negative time, because it is derived from `restEndsAt`.

**Force stop or reboot.** Android removes the alarm and the notification. Nothing re-arms until the app
opens; then the repository's first `sync` re-posts the notification (Over if the end has passed) and
re-schedules the alarm if it hasn't. No buzz is owed for a rest that ended while the phone was off: the app's
effect skips a buzz more than 2 s late (keep the existing `wait > -2000` rule).

**Alarm late (Doze, battery saver).** `setWindow` can be deferred. The screen and notification still show
Over on time. Only the buzz and the background swap are late. Accepted; a phone check covers it.

**Set logged from two places at once.**
- *Same set twice* (shade Log, then the app's tick or a double tap): every log goes by set id inside one
  `repo.update`, and a set already done is a no-op. Only one entry, and only one rest start.
- *App tick on a stale screen:* today `toggleDone` *toggles*. A tap on a set the shade just logged would
  **untick** it. After the merge, the tick sends an intent: "log set X" if it was shown unticked,
  "untick set X" if shown ticked. A stale "log" is a no-op.
- *Two different sets* (shade logs set 2 while the app logs set 3): both survive, because `update` retries on
  conflict. The rest belongs to whichever applied last. That's fine; both are fresh.
- *Index drift:* the app addresses sets by index. If an exercise is moved between render and tap, the index
  can point at another set. The merge resolves index → id inside the same `update`; moving the UI to pass ids
  is a later cleanup (3.4).
- *Lock-screen editor open while the set is logged elsewhere:* `Session.log` refuses (already done), so the
  values typed there are dropped silently today. See decision D7.
- *Tap after the workout ended:* the workout id doesn't match, so it's a no-op (exists today, keep the test).

## 5. Merging the two logging paths into Session (3.2)

New pure, id-based functions in `data/Session.kt`. Each is one `repo.update` from any surface:

```kotlin
// values == null: log with the values the set shows (notification Log, app tick).
// values != null: log with these (app stepper, lock screen). Bodyweight-zero rule applied here only.
// Idempotent. Ends the current rest; starts a new one if auto-rest is on and the exercise isn't cardio.
fun log(d, workoutId, setId, values: SetEntry?, applyToRemaining: Boolean = false, now): AppData
fun untick(d, workoutId, setId): AppData                    // done = false; rest untouched (D6)
fun setValues(d, workoutId, setId, values, applyToRemaining): AppData  // no tick, no rest (today's updateValues; 1.3 reuses it)
fun startRest / adjustRest / skipRest                        // adjustRest no longer clears
fun rest(w, now): RestPhase; fun signedSecondsLeft(w, now): Int
```

| Today | After |
|---|---|
| `AppViewModel.toggleDone` (tick branch) | `Session.log(…, values = null)` |
| `AppViewModel.toggleDone` (untick branch) | `Session.untick` |
| `AppViewModel.logValues`, `logSet` | `Session.log(…, values, applyToRemaining)` |
| `AppViewModel.updateValues`, `updateSet` | `Session.setValues` |
| `AppViewModel.restAfter` | gone (inside `log`) |
| `Session.logSet` | `Session.log(…, values = null)` |
| `Session.logSetAs` | `Session.log(…, values)`; the "reps > 0" check generalised to `Measure.hasValue` |
| private `Session.tick`, `bodyweightZero` | private helpers of `log` |

The view-model functions keep their index-based signatures so screens and the existing test suites don't
churn. Each resolves `(ex, set)` → set id inside the `update` lambda and calls `Session`. `toggleDone` loses its
unused parameter (3.9). `RestActionReceiver` and `LockEditActivity` call the same functions.

## 6. Build order

## Build status

Updated after each step. Phone checks are being held until the end (step 12's checklist).

| Step | State | Commit | Notes |
|---|---|---|---|
| 1 Buzz spike | Done | `f1224ba` | D1 option (b). `RestAlarm.buzzOnce`, `rest_end` channel, debug button "Test rest buzz in 30 s" in Metrics. Not yet phone-tested; fall back to (a) if it fails. Tests check the channel and notification, not `ShadowVibrator`. |
| 2 `Session.rest` | Done | `ba4a428` | `RestPhase`, `signedSecondsLeft` (rounds away from zero). |
| 3 `Session.log / untick / setValues` | Done | `628deb3` | Rep sets now use `Measure.hasValue` (reps not null), not reps > 0. |
| 4 App onto `Session` | Done | `6ad08bf` | `toggleDone` gained `shownDone`; kept the unused `previous` parameter to avoid churning tests (3.9 later). |
| 5 Notification and lock screen onto `Session` | Done | `2b47aec` | `logSet`, `logSetAs`, `tick` deleted; `LockEditTest` and `SkipSetTest` calls switched to `Session.log`. |
| 6 Rest rules | Done | `b85ce64` | `adjustRest` no longer clears; +15 from Over restarts from now; -15 from Over ignored. Until step 9 the `ForgeApp` effect still clears a past-zero rest. |
| 7 to 12 | Not started | | Step 9 is the open owner of the rest end. Until it lands, the foreground `LaunchedEffect` in `ui/ForgeApp.kt` and `RestAlarm.kt`'s `REST_END` still clear the rest at zero, and the "Rest's up" heads-up (`restUp`) still exists. |


Each step is small, leaves the app working and ends green on `./gradlew testDebugUnitTest`.
`FinishToHomeTest` passes as of Phase 3.6, so there are no known failures.

1. **Buzz spike (D1).** Add `RestAlarm.buzzOnce(ends)` with the compare-and-set and the chosen vibration
   attributes; nothing calls it yet except a debug path.
   *Test:* `RestAlarmTest`: same `ends` twice gives one vibration (Robolectric `ShadowVibrator`), a new `ends` buzzes again.
   *Phone:* start a 30 s rest, lock the phone, swipe the app from recents, and wait: one buzz with the screen
   off. Repeat with battery saver on. Build (b) first; switch to (a) only if (b) fails (D1).
2. **`Session.rest` and `signedSecondsLeft`.** Pure only.
   *Test:* `RestPhaseTest` with null, end in the future, end == now, end in the past, and a negative-seconds rounding check (−0:01, not −0:00).
   *Phone:* none (no behaviour change).
3. **`Session.log / untick / setValues`**, unused by surfaces yet; includes the "logging ends the rest" rule
   and the cardio check.
   *Test:* `SessionLogTest`: idempotent repeat, stale workout id, removed set, bodyweight → 0, applyToRemaining,
   T1/T2/T8/T9/T11/T12, untick keeps the rest.
   *Phone:* none.
4. **App onto `Session`.** View-model `toggleDone / logValues / logSet / updateValues / updateSet` delegate; delete
   `restAfter` and the duplicate bodyweight rule.
   *Test:* the existing UI suites unchanged (`AssistedTest`, `SkipSetTest`, `UpcomingTest`, `TrackingTest`,
   `StepperTest`, `LockEditTest`) plus one new test: the shade logs set 0, then `vm.toggleDone(0, 0)` built from the stale "unticked" view leaves it ticked.
   *Phone:* a normal in-app session: tick, stepper log, untick, edit a logged set.
5. **Notification and lock screen onto `Session`.** Receiver LOG → `Session.log`, `LockEditActivity.log` →
   `Session.log(values)`; delete `logSet / logSetAs / tick`.
   *Test:* `RestNotificationTest` and `LockEditTest` pass unchanged.
   *Phone:* log from the shade and from the lock-screen editor, then open the app and check both sets.
6. **Rest rules.** `adjustRest` stops clearing (T7); +15 from Over per D2.
   *Test:* `SessionLogTest`/`RestPhaseTest` additions; update `plusFifteenAndSkip`.
   *Phone:* −15 down past zero in the app; for now it still shows "tap to rest" (fixed in step 8).
7. **Notification renders three states.** Root ids on both layouts, `notif_bg_rest.xml`, kicker per phase,
   chronometer counts down in Resting and Over, lime timer in Over, progress hidden in Over, buttons per D4.
   *Test:* `RestNotificationTest`: phase per state built with `restEndsAt` in the past or future; Roborazzi shots
   `9.1_rest_notification` (resting), new `9.1c_over`, collapsed versions.
   *Phone:* pull the shade during a rest and after zero (it's still cleared at zero until step 9, so check Over via a −15 past zero).
8. **`RestPill` renders three states** from `Session.rest`; Working per D5.
   *Test:* Roborazzi shots of the pill in each state; a UI test that −15 past zero shows `−0:0x` and Skip.
   *Phone:* the same −15 check in the app.
9. **Zero writes nothing (3.3).** `ForgeApp` effect: `buzzOnce` only, gated on the lifecycle being STARTED (not
   `inForeground`), no `skipRest`. `REST_END`: no write, no `inForeground` check, `buzzOnce` + `sync`. Remove
   `ForgeActivity.inForeground` if nothing else uses it.
   *Test:* rewrite `restEndingInTheBackgroundPostsRestsUp` → `restEndingLeavesTheRestOnDisk` (end on disk unchanged,
   notification phase Over, one buzz); a test that the app effect and the alarm together buzz once.
   *Phone:* rest to zero with the app open (one buzz, card flips to Over and keeps counting), with the screen off,
   and with the app swiped away.
10. **Retire the heads-up.** Delete `restUp`, `notif_rest_up.xml`, `REST_UP_ID` cancels and the `CH_REST` channel
    (per D8). Add Edit to the ongoing notification (opens `LockEditActivity`).
    *Test:* drop `shot9_3_restsUp`; a new test that Edit's pending intent targets `LockEditActivity` with the set id;
    `LockEditTest` updated for the new entry point.
    *Phone:* a full session from the lock screen: rest → zero → negative → Edit → log, and no pop-up appears.
11. **Lock editor against concurrent logs (D7).** `LockEditScreen` collects `repo.data` instead of a snapshot and
    reacts when its set is logged elsewhere.
    *Test:* `LockEditTest`: open the editor, log the set via the receiver, and the editor behaves per D7.
    *Phone:* open Edit on the lock screen, pull down the shade over it and tap Log, then check the editor.
12. **Docs.** Mark 1.1, 3.2 and 3.3 done in `docs/advanced-plan.md`, update CLAUDE.md's "Live session logic is split" note, and add an `AI_LOG.md` entry.
    *Phone:* the full checklist from "Checking each phase" in the advanced plan.

## 7. Decisions (agreed 13 Nov 2026)

- **D1. Background buzz:** option (b), a one-off alerting re-post on a quiet `IMPORTANCE_DEFAULT` channel
  (no sound, no heads-up), so it respects Do Not Disturb and the user's channel settings. Fall back to (a),
  alarm-usage vibration, only if the step 1 phone test shows (b) can't switch channels and back cleanly.
- **D2. +15 when Over:** `end = now + 15 s` ("give me 15 more"), not added to the old end.
- **D3. −15 when Over:** hidden.
- **D4. Notification when Over:** Log, Edit, Skip and +15. Kicker per state: "WORKOUT" / "REST" / "OVER".
- **D5. App card in Working:** shows the session clock, counting up, to match the notification. The label stays
  "TAP TO REST" and a tap still starts the default rest.
- **D6. Unticking a set:** leaves the rest alone.
- **D7. Lock editor when its set gets logged elsewhere:** switches to the new next set with a one-line note
  ("Set 2 was logged. Now set 3."). It never puts the typed values on another set without asking.
- **D8. Old "Rest's up" channel:** deleted (`deleteNotificationChannel`) in step 10.
- **D9. Long Over:** no cap in the first build. Revisit after phone testing if a long negative timer bothers you.
- **D10. Resting colours:** lime card, ink text. Record it under Phase 4's lime rule ("success and the one
  primary action") when that rule is rewritten.
