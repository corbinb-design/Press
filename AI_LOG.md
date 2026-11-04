 [21:53] Design pass (~5 min). Claude + Figma drafted the new screens
  (check-in, note chips, evidence chips, Accept tweaks, Sunday issue lines)
  using the Press design system.
  Correction: its red was harsh and off-brand → directed it to use the
  brand red from the existing palette.
## [22:05] Kick-off: Claude Code reads brief + explores codebase (no code)
**Asked:** read BUILD_BRIEF.md, look at the designs, map the codebase, flag conflicts, plan phase 1. No code yet.

**AI caught: conflicts between my brief and my own code**
- Press has no HTTP library or network permission (it's fully offline). The brief assumed one existed.
- A key in BuildConfig would ship inside release APKs.
- The file import can't take TCX (backup or template only).
- The streak is a weekly-goal streak (4 sessions, freezes, comeback weeks), already scored for the Scotland week. My "1 gym session counts" rule would have quietly dropped the goal.
- Existing progression rules differ from the brief's "top reps twice" rule. The "Today's target" card would still offer +2.5 kg on an amber day.
- There's no pre-workout screen: play starts the session straight away.
- Muscle names don't match: 12 in Press vs 9 in my prompt ("arms").
- Smaller ones: Balance warns in amber below 8 sets, not red; the "sets short" line in the Sunday issue also needs suppressing; the Sunday issue is built synchronously, so an AI headline needs caching.
- It couldn't open Figma (403) or confirm my model IDs, and said so instead of guessing.

**My decisions**
- **API key:** in all local builds; no public APK with a key; README note; clean fallback with no key.
- **Network:** add INTERNET for the two AI calls only; everything else stays offline-first.
- **Streak:** keep the weekly goal. 2+ outside sessions totalling 3h+ count as meeting it; confirming context rebuilds the streak.
- **Progression:** keep the existing engine as the source of truth. Readiness is a gate on top:
  - GREEN: as now
  - AMBER: never increase (hold / −5% / drop a set)
  - RED: −10% / drop a set
- **Placement:** check-in and Accept tweaks at the top of the live session, before the first set.
- **Cut:** TCX importer (Health Connect has the data), heart-rate permission (avgHr).
- **Prompt A:** uses Press's 12 muscle names directly.
- **Sunday issue:** suppress the "sets short" line too; AI headline cached per week, template as fallback.
- **Demo snapshot:** the app saves its own Health Connect read to JSON (debug only), instead of me exporting data by hand.

**Takeaway:** planning before coding paid off. The AI found 6 places where my brief didn't match my code, and I made the product calls on each.

## [22:17] Phase 1 built: Health Connect reader + note parsing + check-in
**Asked:** build phase 1 to the 5100 Inputs frames (I.1–I.4), using existing components; commit when it builds.

**AI got wrong / had to fix**
- Planned the "latest stable" Health Connect client (1.1.0), but it needs compileSdk 36 and AGP 8.9.1+. Pinned 1.1.0-beta01, the newest that builds on compileSdk 35 / AGP 8.7.3, instead of upgrading the toolchain mid-build.
- The new check-in sheet covered the live session in 19 existing UI tests. They now tap Skip first, as a user would (`skipCheckIn()` in tests).
- Small slips it fixed itself: a private enum name clashed with one in FinishFlow.kt; a test name had a ";" in it.

**AI flagged, didn't guess**
- My model IDs were still "[exact ID]" placeholders. They're read from local.properties (`GEMINI_MODEL` / `GEMINI_MODEL_FALLBACK`), defaulting to the brief's IDs.
- FinishToHomeTest (2 tests) already fails on the commit before phase 1. Existing bug, out of scope; left alone.

**Added beyond the brief (my call to keep or cut)**
- Prompt A gained `questionMuscles`, so the design's "Yes, add them" can add the muscles the model asked about. Validated like every other field.

**Gemini:** not run yet. No key on the build machine, so only the no-key fallback (fill in the chips yourself) has run.

## [22:27] Phase 1 follow-ups: key, models, snapshot in Profile
**Asked:** check the key is read (without printing it), add the model IDs, real timestamps, ignore docs/Design/, snapshot save/load in release builds (Profile), known-issue note, release APK.

**AI caught**
- My model IDs were "[exact ID]" placeholders again. Writing them as given would have made every call fail. It checked the key's own model list instead: `gemini-3.8-flash` and `gemini-3.5-flash-lite` both exist exactly as in the brief, so those went into local.properties.
- First live Gemini call: the main model answered **503 "high demand"**, not 429, so the fallback never kicked in. Now any 429 or 5xx tries the fallback model.
- The release APK built on this PC is signed with the **debug key** (no keystore.properties here) and has versionCode 4 (the commit count). My phone's release app won't accept it as an update unless both match. Uninstalling would lose my data, so: export first, or bring the keystore over.

**Gemini, live:** "Hiking in Scotland, 4 days, heavy pack" → Hiking · 4 days · High · Quads, Hamstrings, Glutes, Calves, Core, Shoulders, Back; confidence 0.85; asked whether traps and forearms were loaded too. Schema and validation both held.

**My decisions:** stay on Health Connect 1.1.0-beta01; leave FinishToHomeTest (README known issue); keep questionMuscles and the Home card; snapshot save/load moved to Profile → Your data in every build.

**Open at [22:30]:** how to install (keystore onto this PC, or export → uninstall → import); follow-ups not committed yet.

## [22:51] Phase 2 built: rules engine + tests (not committed yet)
**Asked:** check what my saved snapshot says for 22–26 Oct; build the rules engine (readiness, gating, break cap, outside load, streak, everyday movement, facts), with my snapshot as a fixture; show readiness for my next workout; commit as phase-2.

**AI flagged, didn't guess**
- The snapshot isn't on this PC (saved on the phone; Google Drive isn't mounted here). The 22–26 Oct check and the next-workout readiness both wait for the file, plus a Press backup: readiness, the break cap and "next up" need my history, which is only on the phone.
- Built and tested on a fixture made from the brief's real 23–24 Oct numbers instead. A RealDataTest runs on my files as soon as they're copied in. Holding the commit until it has.

**AI got wrong / had to fix**
- Two test expectations were its own arithmetic slips (a distance sum; it assumed the 15,000-step floor would apply, but the trip lifted the 28-day average so 1.5× won: 15,092). The engine was right both times. Also semicolons in test names again.

**Design calls it made (mine to overrule)**
- Outside context is stored per day in forge.json (derived totals only, no raw records), so the streak, Balance, the Sunday issue and its notification work without a live Health Connect read. Health Connect is now read on every app open.
- Outside weeks stay counted once earned, even after Health Connect forgets them.
- A "Other" outing named by a note keeps the watch's label (for the evidence sheet later).
- Balance gets a plain ice "Outside the gym" label now; the designed pill comes in phase 4.

## [22:58] Phase 2 on my real data, then committed
**Asked:** real-data tests from the git-ignored private folder (skip cleanly without it); the committed fixture stays synthetic; give me the 22–26 Oct readout and my next workout; commit phase-2.

**Real data vs my brief**
- Health Connect has **2 hiking days, not 4**: Fri 23 (merged 4h06, 11.4 km, 263 m: a walk + the split hike) and Sat 24 (merged 4h38, 13.4 km, 296 m). Thu 22 and Sun 25 have steps and short walks only.
- Sat 24 is **"Hiking" in Health Connect, not "Other"**, with **201 m** of climbing on the long hike, not the 540 m from the TCX export. So the 300 m climbing rule never fires on my real data.
- Merging worked as designed: the split hike plus the walk before it became one outing; the 15:48 walk stayed separate (2h13 gap).
- Watch "sleep" of exactly **2h 14** on 23 Oct and 26 Oct, though I don't wear the watch to bed. It replaces my check-in answer and makes 26 Oct red.

**AI caught a real bug**
- My watch saved the 1 Nov gym session (Upper A, logged 15:19) as **"Other" titled "CrossFit", 1h19**. As a 60-min+ "Other" it counted as outside, so W44 met the outside rule and the gym counted twice. Fix: a watch session that mostly overlaps a logged workout is that workout, dropped before merging. Rechecked on every sync and after finishing a workout.

**Result on my real data**
- Streak: W42 had 2 of 4 sessions (no streak yet); the Scotland week (1 gym + 2 hiking days, 8h44) now counts → streak 1; W44 (1 session) is a miss → repair quest this week (5 sessions). Without context the streak would be 0.
- 26 Oct (first session back, 7 days): RED, partial: watch sleep 2h14 + 4h38 hiking and a 25k-step day. Capped: no increases.
- Next workout (Lower A, 2 Nov evening): GREEN, partial (no check-in), 1 day since the gym, no recent load. Five of six lifts have no history yet, so the engine sets a baseline; Seated Calf Raise holds at 154 kg × 12.
- Everyday movement, W43: 116,045 steps, 50.3 mi (81 km); high-step threshold 17,154 (1.5× my 28-day average beats 15,000); high days Fri 23 (33,377) and Sat 24 (25,383).

**Open for me to decide:** should a 2h14 watch "sleep" replace my check-in? AI suggests only trusting watch sleep of 3h+, else using my answer.

## [23:14] Phases 3–4 built: coach call + Sunday issue v2 + Accept tweaks
**Asked:** Prompt B coach call with Kotlin validation and template fallback, cached per session and per week; the Accept tweaks banner; Sunday issue v2; tested on my private data; release APK; commit, tag, push. Also: watch sleep only counts from 3h.

**Built**
- Coach: Gemini picks one allowed action per exercise and writes the words; Kotlin rejects the whole reply if any action isn't allowed, any fact ID is unknown, an exercise is missing or doubled, the headline/summary is over 8/60 words, or a number in the words isn't in any fact. Then the template.
- Banner at the top of the live session after the check-in: readiness dot, summary, evidence chips (tap → the fact), before → after per tweak, Accept / Ignore; after Accept a pill with Undo. Template first, AI words replace it if they pass.
- Sunday issue v2: AI headline + summary for the latest issue (cached; back issues keep the template), "Outside the gym" line, everyday-movement line, evidence chips, Streak · Gym · Outside.

**On my real data**
- 26 Oct (first back, poor sleep): RED, capped. The AI chose "drop a set" for every lift; all valid.
- Scotland issue (template): "One in the gym, two outside." · 2 hiking days · 24.8 km · 559 m climbed · "You covered serious ground: 116,045 steps and 50.3 miles". Legs no longer called short.

**AI caught / had to fix**
- Validation caught Gemini inventing a number: it wrote "550 m" for my real 559 m. The reply was dropped for the template, as designed.
- The weekly issue call to the main model kept timing out at 15 s. A timeout now also falls back to the fast model (each request keeps its own 15 s limit).
- The banner pushed the exercise tiles down in two UI tests. Tests now Skip and Ignore like a user, and never call Gemini.

**Known gap:** numbers written as words ("over fifty thousand steps") aren't checked by the number rule.

## [23:22] Preview issue (Profile → Your data)
**Asked:** a way to test the Sunday issue today and record the Scotland week for the demo: pick a recent week, write its issue now with the AI (template fallback, same validation), regenerate, and change nothing.

**Built:** Profile → Your data → Preview issue, in every build. Lists the last 8 weeks; the chosen week's issue is written now through the same call and checks. A line says whether it's AI text or the template, and why it fell back. Regenerate asks again. Nothing is saved: the archive, the cached headline and the Sunday notification stay as they were (tested).

## [23:22] ⏸ Clock paused
Paused at 23:22. Resume time to be logged here when the clock restarts.

## [18:22, 4 Nov] ▶ Clock restarted: final fix round
**Asked:** A) bugs: Preview → Regenerate opened Unlocks; raw error text in the preview; shared image barer than the in-app issue; check-in colours by meaning; GREEN-day banner "1 tweak" with no visible change. B) "outside" made general, not hiking-only. C) repair quest folded into the streak pill. Step goal is out of scope (README "Next").

## [18:29] A: bug fixes
- **Regenerate opened Unlocks:** not a wrong route. Overlays didn't swallow touches, so a tap on a disabled spot (REGENERATE while it was writing) or on empty space fell through to Profile's "Unlocks" row underneath. Every overlay now blocks taps (`blockTaps()`).
- **Raw errors in the preview:** failures now carry a friendly reason: "AI busy…" (429/5xx/timeout), "Offline…" (no network), "AI reply dropped: it used a number Press didn't compute", plus "AI unavailable" / "didn't pass Press's checks" for the rest. Raw detail only behind a tap in debug builds. The note confirm screen uses the same short reasons.
- **Share poster:** now has the outside line, the everyday-movement line, and Streak · Gym · Outside (same numbers as the in-app issue, now on `Issue`).
- **Check-in:** the selected answer is red / amber / green by meaning (soreness reversed); unselected stay neutral, "See today's plan" stays lime. No green token existed, so `Forge.Green` reuses the Legs template green.
- **GREEN "1 tweak":** that tweak was a hold at the same weight (e.g. calf raise 154 → 154). A hold that changes nothing isn't counted; the banner says "No changes today", and the pill names the change ("Tweaks applied · Squat 70 → 72.5 kg").
- Tests: 32 suites green, plus a new one for the reasons and holds.

## [18:35] B + C: "outside" made general; repair quest in the streak pill
- **Labels by session type:** a week's outings are summarised as "2 walks · 1 hike" (from the watch's types; "Other" reads "other session"). The issue's outside section takes that as its label; the icon is the type's own (mountain for hikes, steps for walks) or a new neutral compass for anything else or a mix.
- **Sunday issue:** "Outside the gym: 2 days · 24.8 km on the move · 559 m climbed"; everyday movement: "You covered serious ground: 116,045 steps this week, all walking: 81 km (50.3 mi)." Same lines on the share poster.
- **Home card:** "Walks, runs, rides, sport or time off. Tell Press what happened." When there's no recent note but the watch counted outings, it says what it saw ("2 walks · 1 hike on your watch…"). Connect-screen and rationale copy no longer assume hiking.
- **C:** the separate Repair quest card is gone; the pill reads "6 WK · REPAIR 2/5" with a lime border. Tapping it opens the quest details, with "Pause streak instead" below (pause was the pill's old tap).
- **Out of scope, noted in README "Next":** daily step goal (tester request).
- Tests: all suites green, plus OutsideLinesTest. Checked the Home pill in a screenshot.

## [18:36] Release APK built
- Commits: `9ee8027` (A), `70cbc88` (B + C), `d71fca3` (README "Next": step goal). My B+C log said the README was done; the edit had silently missed (CRLF), and git status caught it, so it went in its own commit.
- `app/build/outputs/apk/release/app-release.apk`. Built with the key from local.properties, so for my phone only (see README).

## [18:52] Phone testing feedback
**Asked:** two-line Home header with the streak pill on its own row; a minimal step card; a general "What happened?" screen with pills only for recurring notes; release APK, commit, push, move the `after` tag.

- **Header:** "Evening, Corbin" (Morning before 12, Afternoon before 18) / "Ready to lift?". The streak pill ("6 WK · REPAIR 2/5") sits on its own row, right-aligned under the profile picture. Checked in a screenshot.
- **Step card:** "Steps today 9,012 / 10,000" with an ice bar, and "+1,240 extra" past the target. Today's steps come from the Health Connect read already stored per day, and the card is hidden when there are none. Profile → Training → Daily steps lets you set any target, e.g. 12,500 (default 10,000). No XP and no streak effect. README "Next": target bonuses, and big days counting toward the streak.
- **"What happened?":** new placeholder; the fixed pills are gone. A pill shows only when a one-day activity is confirmed in 2+ notes on the same weekday ("Football on Sunday"), or the same activity in 2 notes within 28 days ("Walking"). With no pattern there are no pills (RecurringNotesTest).
- **AI slip, caught at compile:** my sed for the pills also matched three FlowRows on the confirm screen. I removed them and checked the diff is only the intended change.
- [18:55] Tests green; release APK built.
- [18:56] Pushed main; `after` moved to this commit (force-pushed).
