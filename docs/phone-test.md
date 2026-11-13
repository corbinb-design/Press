# Phone test checklist

Checks held until the end of a batch of steps. One heading per step; tick them all off in one pass.

## Rest design step 7: notification renders three states

Needs a debug build installed, notifications allowed, and Profile rest notification on.
Edit is not in the notification yet: it arrives in step 10 (D4), so don't look for it here.
Until step 9, the app and the alarm still clear the rest at zero, so Over is only reachable with -15.

- [ ] **Working:** start a workout, don't log anything. Pull the shade: dark card, `WORKOUT` kicker, timer counting up, only Log set (no +15, no Skip, no progress bar).
- [ ] **Resting:** log a set. Shade shows a lime card with dark text, `REST` kicker, timer counting down, a progress bar filling, and Log set (dark button), +15 and Skip.
- [ ] **Resting, collapsed:** collapse the notification. Lime background, dark countdown, next set on the right.
- [ ] **Over:** log a set, open the app and tap -15 until the rest passes zero. Pull the shade: dark card, `OVER` kicker, lime timer showing a negative time (`-0:05`) that keeps counting, no progress bar, Log set, +15 and Skip.
- [ ] **Over, collapsed:** same, dark card with the lime negative timer.
- [ ] **+15 from Over:** tap +15 in the notification. Back to a lime Resting card at about 0:15.
- [ ] **Skip:** from Resting and from Over, Skip returns to the dark `WORKOUT` card with the clock counting up.
- [ ] **Lock screen:** lock the phone during a rest and again when Over. Card shows the right state. With "Log from lock screen" off, the countdown shows but there are no action buttons.
- [ ] **Readability:** on the lime card, check the muted text (kicker, exercise name) and the pill buttons are legible in daylight-ish brightness.
- [ ] **Watch (if paired):** the mirrored notification title reads `Rest 1:23` while resting and `Rest over` when over.

## Rest design step 8: the app's rest pill renders three states

Same caveat as step 7: until step 9 the app still clears the rest at zero, so Over is only reachable with -15.

- [ ] **Working:** start a workout, log nothing. The pill at the bottom reads `TAP TO REST` with the session clock counting up (not the default rest time). Tap it: it starts the default rest.
- [ ] **Resting:** log a set. The pill is solid lime with dark text, `REST`, a countdown, and -15, +15 and SKIP (SKIP is a dark button with a lime label). The slide-up on logging still plays.
- [ ] **Over:** from Resting tap -15 until it passes zero. The pill turns dark with a lime outline, `OVER`, and a lime negative timer (`-0:05`) that keeps counting. Only +15 and SKIP show; there is no -15.
- [ ] **Tapping Over:** tapping the timer itself does nothing (it doesn't restart a rest).
- [ ] **+15 from Over:** back to a lime Resting pill at about 0:15.
- [ ] **Skip:** from Resting and from Over, SKIP returns to the Working pill with the clock counting up.
- [ ] **Readability:** on the lime pill, the dim -15 / +15 chips and the `REST` label are legible.

## Rest design step 9: reaching zero writes nothing

The rest is no longer cleared at zero by the app or the alarm; it turns Over by the clock. Needs a debug build, notifications allowed, Profile rest notification on. Use a short rest (Profile rest time, or -15 down to a few seconds).

- [ ] **App open:** log a set and watch the pill to zero. One buzz. The pill flips to dark `OVER` with a lime negative timer that keeps counting (it does not go back to `TAP TO REST`).
- [ ] **App open, notification:** pull the shade after zero. Dark `OVER` card, timer negative and still counting, Log set, +15 and Skip. No separate "Rest's up" pop-up should be needed, but one may still appear until step 10 (old heads-up not removed yet; ignore it).
- [ ] **Screen off:** log a set, lock the phone, wait. One buzz at zero (not two). Unlock: the shade shows Over with the true negative time.
- [ ] **App swiped away:** log a set, swipe the app from recents, wait. One buzz. Reopen: the pill shows Over with the negative time, not `TAP TO REST`.
- [ ] **Buzz count:** with the app open, then backgrounded just before zero, confirm it is one buzz per rest, never two.
- [ ] **Quiet channel:** the buzz makes no sound and no heads-up (D1). If it is silent on a background rest, D1 option (b) failed: switch to (a).
- [ ] **Skip / +15 / log after Over:** Skip returns to Working; +15 restarts at about 0:15; logging the next set starts a fresh rest. Each buzzes once at its own zero.
- [ ] **Battery saver on:** repeat the swiped-away check. A late buzz is accepted (Doze); the notification should still read Over.

## Rest design step 10: the "Rest's up" heads-up is gone, Edit is in the notification

Needs a debug build installed over an older one (so the old "Rest's up" channel exists to be deleted), notifications allowed, Profile rest notification on, "Log sets from the lock screen" on.

- [ ] **No pop-up:** log a set and let the rest run to zero, with the app open, in the background and with the screen off. No "Rest's up" heads-up appears. You still get one buzz and the ongoing notification flips to Over.
- [ ] **Old channel removed:** Android Settings, Apps, Press, Notifications. "Rest's up" is no longer listed; "Workout in progress" and "Rest ended" are.
- [ ] **Edit only when Over:** Working and Resting show Log set, +15, Skip as before (no Edit). Over shows Log set, Edit, +15 and Skip in one row; check all four labels fit and none is cut off.
- [ ] **Lock screen, full run:** lock the phone, log a set from the notification, let rest hit zero and go negative, tap Edit. The compact stepper appears over the lock screen without unlocking. Change weight or reps, tap Log set: it closes, the set is logged with your values, a new rest starts and the notification goes back to lime Resting.
- [ ] **Edit while unlocked:** with the phone unlocked, tap Edit in Over. It opens the app's own stepper for the next set.
- [ ] **Edit with lock-screen logging off:** turn "Log sets from the lock screen" off, lock the phone, tap Edit. It asks to unlock, then opens the app's stepper.
- [ ] **Edit on a non-reps set** (timed or cardio next): Edit opens the app's stepper rather than the lock editor.
- [ ] **No Edit when nothing is left:** after the last set is logged, no Edit button shows.

## Rest design step 11: the lock editor follows concurrent logs (D7)

Needs the lock-screen setup from step 10: "Log sets from the lock screen" on, a weight-and-reps next set.

- [ ] **Logged from the shade:** log a set, wait for Over, lock the phone, tap Edit (stepper appears over the lock screen). Pull the shade down over it and tap Log set there. The editor stays open, moves to the following set and shows a lime line `Set 2 was logged. Now set 3.` under the exercise name.
- [ ] **Your values stay off the new set:** before the shade log, change the weight with the stepper. After the switch, the stepper shows the new set's planned weight and reps, not what you typed.
- [ ] **Logging from the moved editor:** tap Log set. The new set is logged once, the editor closes, a new rest starts.
- [ ] **Last set:** with one set left, open Edit and log it from the shade. The editor closes by itself.
- [ ] **Next exercise:** if the logged set was the exercise's last, the note names the next exercise (`Set 3 was logged. Now <name>, set 1.`).
- [ ] **Nothing changed elsewhere:** open Edit and just wait. The editor doesn't flicker or move, and the rest clock keeps counting.
