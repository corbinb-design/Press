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
