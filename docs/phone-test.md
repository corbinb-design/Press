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
