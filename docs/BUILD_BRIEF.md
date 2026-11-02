# Press: Context Layer — Build Brief

**Purpose:** a 3-hour build for the Palantir Product Design Show & Tell (Lens A: Make it work).
**Who this is for:** Claude Code, working in the Press repo. Read this whole file before writing any code.

---

## The problem (one paragraph)

Press only sees the gym. I spent four days hiking in Scotland with a heavy pack. Afterwards, the Sunday issue gave me a 0-week streak and 0 records, and told me "Quads are 10 sets short". Balance said "Chest is under target, add Cable Crossover". Any active week off gets read as failure, and the suggestions push more volume when I may not be ready.

**Goal:** teach Press what happens outside the gym and how recovered I am, so its suggestions and the Sunday issue reflect real life.

## What already exists (do not rebuild)

- Native Android app in Kotlin, built with Gradle.
- Workout logging, file import and history.
- Workout suggestions based on past training.
- The Sunday issue ("Issue No. X") and Metrics, with Overview, Lifts, Balance and Cabinet tabs.
- Two users, each on their own phone. There is no shared or partner view.

## What we are building (in scope)

1. **Health Connect reader.** Read the last 14 days on the device.
2. **"What happened" note.** One line of free text, turned into structured context by AI, then confirmed by me.
   - **Plus a three-tap readiness check-in:** sleep, soreness, energy.
3. **Deterministic rules engine.** Readiness, allowed actions, outside load, streak.
4. **AI coach call.** It picks from the allowed actions and writes the explanation.
5. **UI.**
   - Sunday issue v2 with an evidence trail.
   - An "Accept tweaks" banner on the next workout.
   - Balance shows "trained outside the gym" for those muscles.

**Out of scope:** partner or shared views, accounts, server proxy, Google Health cloud API, watch app, existing bug fixes.

---

## Core principle

**Rules handle numbers. AI handles words. I make the final call.**

- **The AI never invents a weight, set count or rep target.**
  - It may only choose an `actionId` from the list Kotlin gives it.
  - Kotlin does all the arithmetic, including plate maths.
  - Kotlin validates every AI response. If anything is invalid, the response is discarded and Press falls back to a template.
- **Every claim shown to the user links to a fact Kotlin computed** (an evidence chip).
- **Nothing changes my workout until I tap "Accept tweaks".**

---

## 1. Health Connect reader

- **Dependency:** `androidx.health.connect:connect-client`. Use the latest stable version.
- **Permissions:** add to the manifest and request with the Health Connect permission contract.
  - `READ_EXERCISE`
  - `READ_STEPS`
  - `READ_DISTANCE`
  - `READ_SLEEP` (optional; used only if data exists)
  - `READ_ELEVATION_GAINED`
- **What my phone actually has** (checked in Health Connect; written by the Fitbit/Health app):
  - **Present:** Exercise, Steps, Distance, Elevation gained, Floors, Calories, Heart rate (daytime).
  - **Not present:**
    - Sleep: I don't wear the watch to bed.
    - HRV and resting heart rate.
  - **Readiness must not depend on any watch data from overnight.** The watch only tells us about **load** (what I did). How I feel comes from a quick check-in (section 2b).
- **Records to read** (last 14 days):
  - `ExerciseSessionRecord`: type, start, end, title.
  - `StepsRecord`
  - `DistanceRecord`
  - `ElevationGainedRecord`
  - `SleepSessionRecord`: only if present. If it exists, it replaces the self-reported sleep answer.
- **Output:** a `ContextDay` per date:
  `date, sleepMinutes?, steps?, outsideSessions: [{type, minutes, distanceKm?, elevationGainM?, avgHr?}]`
- **Watch sessions get split.** In Health Connect, 23 Oct shows as Hike 09:22–10:29 plus Hike 10:51–13:35, with walks before and after. Merge same-day sessions with a gap of 45 minutes or less into one "outing" for display, but keep the original records as evidence.
- **Watch sessions can be mislabelled.** Real example: my 24 Oct hike exported as `Sport="Other"`, at 3h57, 9.5 km and roughly 540 m of climbing.
  - Treat `OTHER` / unknown types of 60+ minutes as outside sessions.
  - Let the confirmed note supply the real type ("hiking").
- **Fallback if the data isn't in Health Connect:** import `.tcx` files exported from the Health app, through Press's existing file import. Parse each `Activity`'s start time, total time, distance, heart rate and altitude, then work out elevation gain from altitude, smoothed to ignore changes under 3 m.
- **Demo snapshot:** a debug-only "Load demo snapshot" toggle that loads a saved JSON of my real 14 days. It's a fallback in case the permission dialogs misbehave while recording.
- **Handle the unhappy paths:**
  - Health Connect is not installed.
  - Permission is denied.
  - There is no data.

  In each case, say so plainly and carry on with the note alone.

## 2. "What happened" note → AI → confirm

- **Input:** free text, for example "Hiking in Scotland, 4 days, heavy pack".
- **The AI call** returns JSON only, constrained by a response schema (see Prompt A below).
- **Confirm step:** show the parsed result as editable chips, such as "Hiking · 4 days · High intensity · Quads, Hamstrings, Glutes, Calves".
  - I tap **Confirm** or edit the chips.
  - Nothing is saved until I confirm.
  - Low confidence is shown in amber, along with any clarifying question.
- **Merging:** combine the confirmed note with Health Connect sessions on matching dates. The watch provides duration and distance where it has them. The note provides type and context.

## 2b. Readiness check-in (three taps)

The watch can't see how I slept or how I feel, so Press asks. It's shown when I open the next workout, or from the Sunday issue.

| Question | Options |
|---|---|
| Sleep last night | Poor · OK · Good |
| Soreness | None · Some · Very sore |
| Energy | Low · OK · High |

- **Skip is allowed.** Readiness then uses load only and is labelled **"partial: no check-in"**.
- **Store** each answer with a timestamp. Answers count as facts with IDs (`checkin.sleep`, and so on), so they appear in the evidence chips ("You said: poor sleep").

## 3. Rules engine (pure Kotlin, unit-testable)

### Readiness
Start at a score of 0. Subtract 1 for each of these:
- **Sleep:** check-in = Poor. Or, if Health Connect has sleep data, under 6 hours.
- **Soreness:** check-in = Very sore.
- **Energy:** check-in = Low.
- **Recent load (from the watch):** 6 or more hours of outside sessions in the last 72 hours, or any session with 300 m or more of climbing in the last 48 hours.

| Score | State |
|---|---|
| 0 | GREEN |
| −1 | AMBER |
| −2 or lower | RED |

Store the reasons as facts, for example:
`{id:"checkin.sleep", text:"You said: poor sleep"}`
`{id:"readiness.recentLoad", text:"[real hours] of hiking in the last 3 days"}`

### Return from a break
If 5 or more days have passed since the last gym session, this is the first session back. Cap all actions at `HOLD_WEIGHT` or lower, whatever the readiness state.

### Allowed actions, per exercise in the next planned workout

| Readiness | Allowed |
|---|---|
| GREEN | `INCREASE_LOAD` (only if top reps were hit on all working sets in each of the last 2 sessions), `HOLD_WEIGHT` |
| AMBER | `HOLD_WEIGHT`, `REDUCE_LOAD_5`, `DROP_ONE_SET` |
| RED | `REDUCE_LOAD_10`, `DROP_ONE_SET` |

- **Load increment:** use Press's existing increment if it has one. Otherwise +2.5 kg for upper body and +5 kg for lower body.
- Kotlin computes the resulting target, rounded to the nearest available plate or step.

### Outside load
- **What counts:** outside sessions of hiking, walking, running, cycling or `OTHER`, at least 60 minutes each, plus any muscles named in the confirmed note. Climbing of 300 m or more in a session counts as a high leg load.
- **Effect:** mark those muscles as "trained outside the gym" for that week.
  - Suppress the "under target" warnings for those muscles.
  - Show the label in Balance.
- **Do not convert outside activity into "equivalent sets".** We don't claim a fake equivalence.

### Everyday movement (tester request, small)
Requested by my partner, the second tester: big walking days should be recognised, not ignored.
- **Weekly totals:** steps and distance (from `StepsRecord` / `DistanceRecord`), plus the week's biggest day. Show distance in miles and km.
- **High-step day:** 15,000 or more steps, or at least 1.5× my 28-day daily average, whichever is higher. Each one becomes a fact, e.g. `movement.highStepDay` = "Sat: 24,310 steps".
- **Load:** a day with 20,000 or more steps counts toward the "recent load" readiness rule as 2 hours of outside activity.
- **Sunday issue:** if the week had at least one high-step day, or weekly steps are 25% or more above the 4-week average, add an "Everyday movement" line, e.g. "You covered serious ground: [real] steps and [real] miles this week." Real numbers only.
- **Cut line:** this is cut first if behind, before the Balance label.

### Streak
A week counts as active if it has at least one gym session, **or** at least 2 outside sessions totalling 3 hours or more.

### Facts list
Every computed fact gets a stable ID and display text, for example:

```
checkin.sleep
checkin.soreness
checkin.energy
readiness.recentLoad
outside.hike
lift.squat.lastTwo
break.daysSinceGym
```

The UI renders evidence chips **only** from this list.

## 4. AI coach call

- **Input:** a JSON payload containing the readiness state, the facts list, the allowed actions per exercise and this week's stats.
  - **Send only derived facts.** No raw heart-rate or sleep streams. This keeps personal data to a minimum on a free tier.
- **Output (schema enforced):**

```json
{
  "headline": "string, max 8 words",
  "summary": "string, max 60 words, plain English, no pseudo-science",
  "choices": [{ "exerciseId": "string", "actionId": "one of the allowed ids", "reasonFactIds": ["fact ids"] }],
  "evidenceFactIds": ["fact ids"]
}
```

### Validation in Kotlin
- Each `actionId` must be in that exercise's allowed list.
- Each fact ID must exist in the facts list.
- If **anything fails:** drop the response and use the deterministic template ("Short sleep, so we're holding your weights today."). Then log the failure.

## 5. UI

### Sunday issue v2
Keep the existing editorial style, type and colours.
- **Headline** from the AI. It falls back to the template if needed.
- **"Outside the gym" line**, for example "4 hiking days · [real km] km". Use real data only.
- **Streak** uses the new rule.
- **Evidence chips**, for example "Because: 4 hikes · 2 short nights · squat hit 8 twice". Tapping a chip shows the underlying fact.

### Next workout: "Accept tweaks" banner
- **Readiness dot** (green, amber or red) with the summary.
- **Each tweak** shown as before → after, for example "Squat 70 → 70 kg (hold)".
- **Buttons:**
  - **Accept tweaks** applies the changes to the workout state.
  - **Ignore** keeps the plan as it is.

### Balance
Muscles trained outside the gym show a muscle-coloured label ("outside the gym"). They don't show a red "under target" warning.

---

## AI setup

- **Provider:** Google AI Studio, free tier.
  - Model: `gemini-3.8-flash`. Fallback: `gemini-3.5-flash-lite`, if the free tier limits are hit (HTTP 429).
  - Confirm both IDs exactly as AI Studio's "Get code" snippet shows them.
  - Keep the model ID in one constant or `BuildConfig` field so switching is a one-line change.
- **Key:** `GEMINI_API_KEY` in `local.properties` (git-ignored), exposed through `BuildConfig`. **Never commit it.**
- **Call:** a single POST to the `generateContent` REST endpoint, using the HTTP and JSON libraries Press already has.
  - Use `responseMimeType: "application/json"` and a `responseSchema`.
  - Timeout: 15 seconds. On failure, use the template.
- **Note:** free-tier data may be used by Google to improve its products. That's why we only send derived facts. When moving to a paid tier, revisit this.

### Prompt A: note → context (system instruction)

> You convert a short note about physical activity outside the gym into structured JSON.
> - Only use what the note says.
> - If something is unclear, lower the confidence and add a short question.
> - Muscles must come from this list: quads, hamstrings, glutes, calves, core, back, chest, shoulders, arms.
> - Intensity is low, moderate or high.
> - Never give training advice.

Schema:
`{activities:[{type, days, intensity, musclesLoaded:[...], detail}], confidence: 0-1, question?: string}`

### Prompt B: facts → coach (system instruction)

> You write the training summary for a strength app.
> - You are given computed facts and, for each exercise, a list of allowed actions.
> - Choose one allowed action per exercise and cite the fact IDs that justify it.
> - Never invent numbers, weights, sets or facts, and never choose an action that isn't listed.
> - Plain, warm, specific British English. No hype, no pseudo-science, no medical claims.
> - Headline: max 8 words. Summary: max 60 words.

---

## Commit plan

Commit after each phase so the diff tells the story:

1. `phase-1: health connect reader + note parsing`
2. `phase-2: rules engine + tests`
3. `phase-3: coach call + validation`
4. `phase-4: sunday issue v2 + accept tweaks`

Keep `AI_LOG.md` up to date with:
- what I asked;
- what Claude Code / Gemini got wrong;
- what I decided.

## Cut line

If behind schedule, cut in this order:

1. The everyday movement line.
2. The Balance label.
3. Sleep from Health Connect. The check-in answer covers it.
4. The Health Connect UI, falling back to the demo snapshot plus the note.

**Never cut:** the rules engine, validation, or the Accept tweaks step.
