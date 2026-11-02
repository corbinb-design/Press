package app.forge

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.forge.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ForgeRepository.get(app)
    val data: StateFlow<AppData> = repo.data

    // ---- Streak (section 02) ------------------------------------------------------------------

    /** Re-checks the streak, e.g. when the app returns to the foreground in a new week. */
    fun refreshStreak(today: LocalDate = Weeks.today()) = edit { d ->
        val s = Streaks.evaluate(d.streak, d.history, d.settings.weeklyGoal, today)
        if (s == d.streak) d else d.copy(streak = s)
    }

    fun dismissStreakNotice() = edit { it.copy(streak = it.streak.copy(notice = null)) }
    fun acceptRepair() = edit { it.copy(streak = Streaks.acceptRepair(it.streak)) }
    fun declineRepair() = edit { it.copy(streak = Streaks.declineRepair(it.streak, Weeks.today())) }
    fun pauseStreak(length: Streaks.PauseLength, reason: String?) = edit { it.copy(streak = Streaks.pause(it.streak, length, reason, Weeks.today())) }
    fun resumeStreak() = edit { it.copy(streak = Streaks.resume(it.streak, Weeks.today())) }

    /** Saves the first-launch choices and replaces the placeholder templates with the chosen split. */
    fun completeSetup(name: String, unit: WeightUnit, weeklyGoal: Int, split: Library.Split) = edit { d ->
        d.copy(
            settings = d.settings.copy(name = name.trim().take(24), unit = unit, weeklyGoal = weeklyGoal.coerceIn(2, 6), onboarded = true),
            templates = split.templates.map { Library.withColor(it.copy(id = newId())) },
        )
    }

    private fun edit(f: (AppData) -> AppData) { repo.update(f) }
    private fun editActive(f: (Workout) -> Workout) = edit { d -> d.copy(active = d.active?.let(f)) }
    private fun editEntry(ex: Int, f: (ExerciseEntry) -> ExerciseEntry) = editActive { w ->
        w.copy(exercises = w.exercises.mapIndexed { i, e -> if (i == ex) f(e) else e })
    }

    val allExercises: List<Exercise> get() = Library.exercises + repo.value.customExercises
    /** With the user's Assisted / Weighted choice applied. */
    fun exercise(id: String): Exercise = Session.exercise(repo.value, id)

    /** C: switches [exerciseId] between Assisted and Weighted (see [Migrate.setMode]). */
    fun setTrackingMode(exerciseId: String, mode: Tracking) = edit { Migrate.setMode(it, exerciseId, mode) }

    // ---- Context layer (docs/BUILD_BRIEF.md) ---------------------------------------------------

    val healthReader = HealthConnectReader(app)
    private val _health = MutableStateFlow<HealthState>(HealthState.Idle)
    /** The latest Health Connect read. Kept in memory only: it's read again rather than saved in forge.json. */
    val health: StateFlow<HealthState> = _health

    /** Reads Health Connect again. A loaded demo snapshot stays until the app restarts. */
    fun refreshHealth() {
        if ((_health.value as? HealthState.Ready)?.demo == true || _health.value == HealthState.Loading) return
        _health.value = HealthState.Loading
        viewModelScope.launch { _health.value = healthReader.read().also(::syncOutside) }
    }

    /** A fresh read is summarised into forge.json; the streak is rebuilt if a week's outside verdict changed. */
    private fun syncOutside(state: HealthState, force: Boolean = false) {
        val snapshot = (state as? HealthState.Ready)?.snapshot
        if (snapshot != null || force) edit { Outside.sync(it, snapshot, Weeks.today(), force) }
    }

    /** "Skip, use a note only": don't ask for Health Connect again before the note. */
    fun skipHealth() = updateSettings { it.copy(healthSkipped = true) }

    /** What the watch recorded, merged into outings, with confirmed notes naming the "Other" ones. */
    fun outings(): List<Outing> = (_health.value as? HealthState.Ready)?.snapshot
        ?.let { LifeContext.withNotes(LifeContext.outings(it), repo.value.contextNotes) }.orEmpty()

    /**
     * The note as chips. Gemini parses it; if there's no key, no network, or the reply fails validation, the
     * chips start from [Notes.manual] and the second value says why, to show on the confirm screen.
     */
    suspend fun parseNote(text: String): Pair<Notes.Parsed, String?> =
        Gemini.generate(Notes.request(text)).mapCatching { Notes.parse(it) }.fold(
            onSuccess = { it to null },
            onFailure = {
                // Logged for AI_LOG.md: a reply that failed validation is dropped whole.
                android.util.Log.w("Notes", "Note parse fell back to manual chips: ${it.message}")
                Notes.manual(text) to (it.message ?: "No reply")
            },
        )

    /** Confirm: saves the note with the dates each activity covers, matched to the watch where it can be. */
    fun confirmNote(text: String, activities: List<NoteActivity>, parsedByAi: Boolean, today: LocalDate = Weeks.today()) {
        val outings = outings()
        val dated = activities.map { a -> a.copy(dates = LifeContext.datesFor(a, outings, today).map(LocalDate::toString)) }
        edit { it.copy(contextNotes = it.contextNotes + ContextNote(newId(), text.trim(), dated, System.currentTimeMillis(), parsedByAi)) }
        // Confirming context always rebuilds the streak, so a week like Scotland is judged again.
        syncOutside(_health.value, force = true)
    }

    /** Three taps before a session, or a skip (all null). Asked once per session. */
    fun saveCheckIn(workoutId: Long, sleep: SleepAnswer?, soreness: Soreness?, energy: Energy?) = edit { d ->
        val c = CheckIn(System.currentTimeMillis(), workoutId, sleep, soreness, energy)
        d.copy(checkIns = (d.checkIns + c).takeLast(LifeContext.CHECK_INS_KEPT))
    }

    private val snapshotJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; prettyPrint = true }

    /**
     * Before "Save snapshot" asks where to save: the current read, or a fresh one if there's none yet (Profile can
     * be the first place Health Connect is read). Anything but [HealthState.Ready] means there's nothing to save.
     */
    suspend fun readForSnapshot(): HealthState {
        if (_health.value is HealthState.Ready) return _health.value
        _health.value = HealthState.Loading
        return healthReader.read().also { _health.value = it; syncOutside(it) }
    }

    /** Writes the current Health Connect read to a file, to load later in its place (Profile, every build). */
    fun saveSnapshot(uri: Uri, onResult: (Boolean) -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        val snap = (_health.value as? HealthState.Ready)?.snapshot
        val ok = snap != null && runCatching {
            getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!.use {
                it.write(snapshotJson.encodeToString(HealthSnapshot.serializer(), snap).toByteArray())
            }
        }.isSuccess
        withContext(Dispatchers.Main) { onResult(ok) }
    }

    /** Uses a saved snapshot in place of Health Connect until the app restarts. */
    fun loadSnapshot(uri: Uri, onResult: (Boolean) -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        val snap = runCatching {
            val text = getApplication<Application>().contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
            snapshotJson.decodeFromString(HealthSnapshot.serializer(), text)
        }.getOrNull()
        if (snap != null) HealthState.Ready(snap, demo = true).let { _health.value = it; syncOutside(it) }
        withContext(Dispatchers.Main) { onResult(snap != null) }
    }

    // ---- Workout lifecycle -------------------------------------------------------------

    fun startWorkout(template: Template?) {
        if (repo.value.active != null) return
        val history = repo.value.history
        val now = System.currentTimeMillis()
        val exercises = template?.exercises.orEmpty().map { te ->
            // A running 10.3 variation is done in the lift's place.
            val id = te.effectiveId(now)
            val ex = exercise(id)
            val tracking = ex.tracking
            val prev = Stats.previousSets(history, id, tracking)
            val prevEntry = history.firstNotNullOfOrNull { w -> w.exercises.firstOrNull { it.exerciseId == id } }
            ExerciseEntry(
                id = newId(),
                exerciseId = id,
                perHand = prevEntry?.perHand ?: false,
                // Cardio is one entry (time, distance, level, heart rate), not sets.
                sets = if (tracking == Tracking.CARDIO) listOf(SetEntry(newId())) else List(maxOf(te.sets, prev.size.coerceAtMost(te.sets + 2))) { i ->
                    SetEntry(newId(), type = prev.getOrNull(i)?.type ?: SetType.NORMAL)
                },
                tracking = tracking,
                bodyweightKg = repo.value.settings.bodyweightKg,
                withBodyweight = Library.isBodyweightHold(ex),
            )
        }
        val templateId = template?.id?.takeIf { id -> repo.value.templates.any { it.id == id } }
        edit { it.copy(active = Workout(newId(), template?.name ?: "Freestyle", templateId, now, exercises = exercises)) }
    }

    fun repeatWorkout(w: Workout) {
        startWorkout(Template(w.templateId ?: -1, w.name, w.exercises.map { TemplateExercise(it.exerciseId, it.sets.size, it.sets.firstOrNull()?.reps ?: 8) }))
    }

    fun renameActive(name: String) = editActive { it.copy(name = name) }

    /**
     * Archives the session. Unticked sets are dropped, or, with [logUnticked] (5.1 "I did them"),
     * logged with the values they show. Empty exercises are dropped. Returns null if nothing was logged.
     */
    fun finishWorkout(logUnticked: Boolean = false): Workout? {
        val active = repo.value.active ?: return null
        val history = repo.value.history
        val d0 = repo.value
        // Skipped sets are never logged as shown; they leave with the other unticked ones, counted.
        val w = if (!logUnticked) active else active.copy(exercises = active.exercises.map { e ->
            e.copy(sets = e.sets.mapIndexed { i, s -> if (s.done || s.skipped) s else Session.fill(d0, e, i) ?: s })
        })
        val ended = w.copy(
            endedAt = System.currentTimeMillis(), restEndsAt = null, skippedSets = Stats.skippedSets(w),
            exercises = w.exercises.map { e -> e.copy(sets = e.sets.filter { it.done }) }.filter { it.sets.isNotEmpty() },
        )
        // Standalone cardio counts toward the week only at 20+ minutes (and with the setting on); decided now.
        val cleaned = ended.copy(countsForGoal = Session.countsForGoal(ended, d0.settings))
        if (cleaned.exercises.isEmpty()) { edit { it.copy(active = null) }; return null }
        // A planned lighter week that was just done is marked and cleared (see Progression.afterSession).
        // Every template is checked: a lighter week planned from Metrics sits on each template using the lift.
        var saved = cleaned
        val templates = repo.value.templates.map { t -> Progression.afterSession(t, saved).also { saved = it.second }.first }
        val done = saved
        edit { d -> d.copy(active = null, history = listOf(done) + d.history, templates = templates) }
        repo.flushInBackground() // a finished session is saved straight away
        // Context layer: a watch session that was really this workout stops counting as outside.
        syncOutside(_health.value)
        return done
    }

    // ---- Progression (section 10) ----------------------------------------------------------------

    /** 10.2 "Use": rewrites the ghost values of the exercise's unticked working sets. */
    fun applyTarget(ex: Int, kg: Double, reps: Int) = editEntry(ex) { e ->
        e.copy(targetSeen = true, sets = e.sets.map { s ->
            if (s.done || (s.type != SetType.NORMAL && s.type != SetType.FAILURE)) s else s.copy(weightKg = kg, reps = reps)
        })
    }

    /** 10.2 "Keep": no change, and the card stays away for the rest of this session. */
    fun keepTarget(ex: Int) = editEntry(ex) { it.copy(targetSeen = true) }

    /** A 10.3 answer for [exerciseId], applied to every template that uses it. */
    fun answerStall(exerciseId: String, answer: Progression.Answer, lighterKg: Double?, variationId: String?, at: Long) = edit { d ->
        d.copy(templates = d.templates.map { t ->
            t.copy(exercises = t.exercises.map { r -> if (r.exerciseId == exerciseId) Progression.answer(r, answer, lighterKg, variationId, at) else r })
        })
    }

    /** Saves the 10.1 rules or a 10.3 answer for one exercise of a template. */
    fun updateRule(templateId: Long, index: Int, rule: TemplateExercise) = edit { d ->
        d.copy(templates = d.templates.map { t ->
            if (t.id != templateId || index !in t.exercises.indices) t
            else t.copy(exercises = t.exercises.mapIndexed { i, r -> if (i == index) rule else r })
        })
    }

    fun cancelWorkout() { RestAlarm.cancel(getApplication()); edit { it.copy(active = null) } }

    // ---- Sets ---------------------------------------------------------------------------

    fun setWeight(ex: Int, set: Int, kg: Double?) = editEntry(ex) { e -> e.copy(sets = e.sets.mapIndexed { i, s -> if (i == set) s.copy(weightKg = kg) else s }) }
    fun setReps(ex: Int, set: Int, reps: Int?) = editEntry(ex) { e -> e.copy(sets = e.sets.mapIndexed { i, s -> if (i == set) s.copy(reps = reps) else s }) }
    fun setType(ex: Int, set: Int, type: SetType) = editEntry(ex) { e -> e.copy(sets = e.sets.mapIndexed { i, s -> if (i == set) s.copy(type = type) else s }) }

    /** Skips a set (unticking it if it was ticked), or with [skipped] false brings it back. */
    fun skipSet(ex: Int, set: Int, skipped: Boolean = true) =
        editEntry(ex) { e -> e.copy(sets = e.sets.mapIndexed { i, s -> if (i == set) s.copy(skipped = skipped, done = false) else s }) }

    /**
     * Ticks a set. Empty fields are filled from the previous session first (Strong's behaviour);
     * if there's still nothing to log, the tap is ignored and false is returned.
     */
    fun toggleDone(ex: Int, set: Int, @Suppress("UNUSED_PARAMETER") previous: SetEntry? = null): Boolean {
        val entry = repo.value.active?.exercises?.getOrNull(ex) ?: return false
        val s = entry.sets.getOrNull(set)?.takeIf { !it.skipped } ?: return false
        if (s.done) { editEntry(ex) { e -> e.copy(sets = e.sets.mapIndexed { i, x -> if (i == set) x.copy(done = false) else x }) }; return true }
        val filled = Session.fill(repo.value, entry, set) ?: return false
        editEntry(ex) { e -> e.copy(sets = e.sets.mapIndexed { i, x -> if (i == set) filled else x }) }
        restAfter(entry)
        return true
    }

    /** Rest starts after a set, but not after cardio. */
    private fun restAfter(e: ExerciseEntry) {
        if (repo.value.settings.autoRest && e.tracking != Tracking.CARDIO) startRest(restFor(e.exerciseId))
    }

    /** The values a set takes from the stepper: everything except its id, type and tick. */
    private fun SetEntry.with(v: SetEntry) = copy(weightKg = v.weightKg, reps = v.reps, durationSec = v.durationSec, distanceM = v.distanceM, level = v.level, heartRate = v.heartRate)

    /** 4.2 stepper: sets the values, ticks the set, optionally copies them to later unticked sets, starts the rest. */
    fun logSet(ex: Int, set: Int, kg: Double?, reps: Int, applyToRemaining: Boolean) =
        logValues(ex, set, SetEntry(0, weightKg = kg, reps = reps), applyToRemaining)

    /** [logSet] for every tracking type: [v] carries weight, reps, time, distance, level and heart rate. */
    fun logValues(ex: Int, set: Int, v: SetEntry, applyToRemaining: Boolean) {
        val entry = repo.value.active?.exercises?.getOrNull(ex) ?: return
        val zero = entry.tracking == Tracking.BODYWEIGHT_REPS || entry.tracking == Tracking.ASSISTED || exercise(entry.exerciseId).equipment == Equipment.BODYWEIGHT
        val logged = if (v.weightKg == null && zero) v.copy(weightKg = 0.0) else v
        editEntry(ex) { e -> e.copy(sets = e.sets.mapIndexed { i, x ->
            when {
                i == set -> x.with(logged).copy(done = true)
                applyToRemaining && i > set && !x.done -> x.with(v)
                else -> x
            }
        }) }
        restAfter(entry)
    }

    /** Changes a logged set's values without touching its tick (4.2 on a set that's already done). */
    fun updateSet(ex: Int, set: Int, kg: Double?, reps: Int?, applyToRemaining: Boolean) =
        updateValues(ex, set, SetEntry(0, weightKg = kg, reps = reps), applyToRemaining)

    fun updateValues(ex: Int, set: Int, v: SetEntry, applyToRemaining: Boolean) = editEntry(ex) { e ->
        e.copy(sets = e.sets.mapIndexed { i, x ->
            when {
                i == set -> x.with(v)
                applyToRemaining && i > set && !x.done -> x.with(v)
                else -> x
            }
        })
    }

    /** Rest after a set of this exercise: the template's per-exercise rest, else the default. */
    fun restFor(exerciseId: String): Int = Session.restFor(repo.value, exerciseId)

    fun addSet(ex: Int) = editEntry(ex) { e ->
        val last = e.sets.lastOrNull()
        e.copy(sets = e.sets + SetEntry(newId(), weightKg = last?.weightKg, reps = last?.reps))
    }

    fun removeSet(ex: Int, set: Int) = editEntry(ex) { e -> e.copy(sets = e.sets.filterIndexed { i, _ -> i != set }) }
    fun togglePerHand(ex: Int, perHand: Boolean) = editEntry(ex) { it.copy(perHand = perHand) }

    fun addExercises(ids: List<String>) = editActive { w ->
        w.copy(exercises = w.exercises + ids.map(::newEntry))
    }

    /** A new entry for [exerciseId]: three empty sets, or one cardio entry; tracking and bodyweight recorded. */
    private fun newEntry(exerciseId: String): ExerciseEntry {
        val ex = exercise(exerciseId)
        val t = ex.tracking
        return ExerciseEntry(
            newId(), exerciseId, if (t == Tracking.CARDIO) listOf(SetEntry(newId())) else List(3) { SetEntry(newId()) },
            tracking = t, bodyweightKg = repo.value.settings.bodyweightKg, withBodyweight = Library.isBodyweightHold(ex),
        )
    }

    /** v1.1: a standalone cardio session, starting with [exerciseIds] (usually one). */
    fun startCardio(exerciseIds: List<String>) {
        if (repo.value.active != null) return
        edit { it.copy(active = Workout(newId(), "Cardio", null, System.currentTimeMillis(), exercises = exerciseIds.map(::newEntry))) }
    }

    /** Swaps the exercise in place; logged sets are kept, unlogged ones are cleared for the new lift. */
    fun swapExercise(ex: Int, exerciseId: String) = editEntry(ex) { e ->
        val t = exercise(exerciseId).tracking
        if (t != e.tracking) {
            // A different kind of exercise: start its sets afresh (logged sets would mean something else).
            newEntry(exerciseId).copy(id = e.id, perHand = false)
        } else e.copy(exerciseId = exerciseId, sets = e.sets.map {
            if (it.done) it else it.copy(weightKg = null, reps = null, durationSec = null, distanceM = null, level = null, heartRate = null)
        })
    }

    fun removeExercise(ex: Int) = editActive { w -> w.copy(exercises = w.exercises.filterIndexed { i, _ -> i != ex }) }
    /**
     * A: moves exercise [id] [by] places (±1) among the ones still to do: not [currentId], not finished.
     * Worked out from the live session, so quick successive moves (a long drag) each land.
     */
    fun moveUpcoming(id: Long, by: Int, currentId: Long) = editActive { w ->
        val upcoming = w.exercises.indices.filter { w.exercises[it].id != currentId && !Stats.finished(w.exercises[it]) }
        val from = w.exercises.indexOfFirst { it.id == id }
        val to = upcoming.getOrNull(upcoming.indexOf(from) + by)
        if (from !in upcoming || to == null) w else w.copy(exercises = w.exercises.toMutableList().apply { add(to, removeAt(from)) })
    }

    /** A: moves the exercise at [from] to [to] in this session only (the finish flow can offer it to the template). */
    fun moveExerciseTo(from: Int, to: Int) = editActive { w ->
        if (from !in w.exercises.indices || to !in w.exercises.indices || from == to) w
        else w.copy(exercises = w.exercises.toMutableList().apply { add(to, removeAt(from)) })
    }

    fun moveExercise(ex: Int, by: Int) = editActive { w ->
        val to = (ex + by).coerceIn(0, w.exercises.lastIndex)
        val list = w.exercises.toMutableList(); list.add(to, list.removeAt(ex)); w.copy(exercises = list)
    }

    // ---- Rest timer ---------------------------------------------------------------------

    // The repository schedules the rest alarm and refreshes the notification whenever these change.

    fun startRest(seconds: Int) = edit { Session.startRest(it, seconds, System.currentTimeMillis()) }

    fun adjustRest(deltaSec: Int) = edit { Session.adjustRest(it, deltaSec, System.currentTimeMillis()) }

    fun skipRest() = edit(Session::skipRest)

    /** Saves now rather than after the usual batching, e.g. as the app goes to the background. */
    fun flush() = repo.flushInBackground()

    // ---- Notification "Edit" (9.3) ------------------------------------------------------------

    private val _editRequest = MutableStateFlow<Long?>(null)
    /** A set id the 9.3 "Edit" action asked to open in the 4.2 stepper. */
    val editRequest: StateFlow<Long?> = _editRequest
    fun requestEdit(setId: Long?) { _editRequest.value = setId }

    private val _shelfDrop = MutableStateFlow<Long?>(null)
    /** 11 C: the session that should join the History shelf with the drop animation, once. */
    val shelfDrop: StateFlow<Long?> = _shelfDrop
    fun dropOnShelf(workoutId: Long?) { _shelfDrop.value = workoutId }

    private val _issueRequest = MutableStateFlow<String?>(null)
    /** A week key the Sunday notification asked to open (12.1 → 12.2). */
    val issueRequest: StateFlow<String?> = _issueRequest
    fun requestIssue(weekKey: String?) { _issueRequest.value = weekKey }


    // ---- Templates ----------------------------------------------------------------------

    fun saveTemplate(t: Template) = edit { d ->
        if (d.templates.any { it.id == t.id }) d.copy(templates = d.templates.map { if (it.id == t.id) t else it })
        else d.copy(templates = d.templates + t)
    }

    fun deleteTemplate(id: Long) = edit { d -> d.copy(templates = d.templates.filterNot { it.id == id }) }

    fun duplicateTemplate(t: Template) = edit { d ->
        d.copy(templates = d.templates + t.copy(id = newId(), name = "${t.name} copy"))
    }

    /** 5.4: applies the proposed changes (see [TemplateDiff]) to the workout's template. */
    fun updateTemplateFrom(w: Workout) = edit { d ->
        d.copy(templates = d.templates.map { t -> if (t.id != w.templateId) t else TemplateDiff.apply(t, TemplateDiff.changes(t, w)) })
    }

    /** 6.2 "Save as template". */
    fun saveAsTemplate(w: Workout): Template {
        val t = Library.withColor(Template(newId(), w.name, w.exercises.map { e ->
            TemplateExercise(e.exerciseId, e.sets.size, Stats.topSet(e)?.reps ?: 8)
        }))
        edit { it.copy(templates = it.templates + t) }
        return t
    }

    // ---- F: sharing templates ------------------------------------------------------------------------

    /** Sent as a file or shown as a QR code; the first time is remembered for the Cabinet. */
    fun markShared() = edit { d -> if (d.firstSharedAt != null) d else d.copy(firstSharedAt = System.currentTimeMillis()) }

    private val _templateImport = MutableStateFlow<String?>(null)
    /** The text of a `.press` file or QR code waiting to be previewed and added. */
    val templateImport: StateFlow<String?> = _templateImport
    fun requestTemplateImport(text: String?) { _templateImport.value = text }

    /** Adds a shared template (never replacing anything); returns it. */
    fun addSharedTemplate(f: TemplateShare.PressFile): Template {
        var added: Template? = null
        edit { d -> TemplateShare.add(d, f).also { added = it.second }.first }
        return added!!
    }

    /** A picked `.press` file's text, read off the main thread; too large or unreadable fails. */
    fun readText(uri: Uri, onResult: (Result<String>) -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        val r = runCatching {
            getApplication<Application>().contentResolver.openInputStream(uri)!!.use { s ->
                // Read at most one byte past the limit (readNBytes needs API 33).
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (out.size() <= TemplateShare.MAX_BYTES) { val n = s.read(buf); if (n < 0) break; out.write(buf, 0, n) }
                val bytes = out.toByteArray()
                require(bytes.size <= TemplateShare.MAX_BYTES) { "too large" }
                bytes.decodeToString()
            }
        }
        withContext(Dispatchers.Main) { onResult(r) }
    }

    /** 7.3 balance fix: appends an exercise to a template. */
    fun addToTemplate(templateId: Long, exerciseId: String) = edit { d ->
        d.copy(templates = d.templates.map { t -> if (t.id == templateId) t.copy(exercises = t.exercises + TemplateExercise.fresh(exerciseId)) else t })
    }

    /** Form cues for a custom exercise (built-in ones come with the library). */
    fun setCues(exerciseId: String, cues: List<String>) = edit { d ->
        d.copy(customExercises = d.customExercises.map { if (it.id == exerciseId) it.copy(cues = cues.map(String::trim).filter(String::isNotEmpty).take(3)) else it })
    }

    fun createExercise(
        name: String, muscle: String, equipment: Equipment,
        tracking: Tracking = Tracking.WEIGHT_REPS, cues: List<String> = emptyList(),
    ): Exercise {
        val ex = Exercise(
            "custom_${newId()}", name.trim(), if (tracking == Tracking.CARDIO) "Cardio" else muscle,
            if (tracking == Tracking.CARDIO) Equipment.CARDIO else equipment, custom = true,
            tracking = tracking, cues = cues.map { it.trim() }.filter { it.isNotEmpty() }.take(3),
        )
        edit { it.copy(customExercises = it.customExercises + ex) }
        return ex
    }

    // ---- History ------------------------------------------------------------------------

    fun deleteWorkout(id: Long) = edit { d -> d.copy(history = d.history.filterNot { it.id == id }) }

    fun setNote(id: Long, note: String) = edit { d -> d.copy(history = d.history.map { if (it.id == id) it.copy(note = note.trim()) else it }) }

    // ---- Settings -----------------------------------------------------------------------

    fun updateSettings(f: (Settings) -> Settings) = edit { it.copy(settings = f(it.settings)) }

    // ---- Cosmetics (13.2) ---------------------------------------------------------------------

    /** Switches the launcher icon. Locked icons are refused. */
    fun setAppIcon(icon: Unlocks.Icon) {
        if (!icon.met(Unlocks.progress(repo.value))) return
        updateSettings { it.copy(appIcon = icon.key) }
        runCatching { AppIcons.apply(getApplication(), icon) }
    }

    fun setSpineFinish(spine: Unlocks.Spine) {
        if (!spine.met(Unlocks.progress(repo.value))) return
        updateSettings { it.copy(spineFinish = spine.key) }
    }

    fun exportJson(): String = repo.encode(repo.value)

    /** Parses a backup without changing anything. Fails on malformed or non-Forge JSON. */
    fun parseBackup(text: String): Result<AppData> = runCatching { repo.decodeBackup(text) }

    fun readBackup(uri: Uri, onResult: (Result<AppData>) -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        val r = runCatching {
            getApplication<Application>().contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
        }.mapCatching { repo.decodeBackup(it) }
        withContext(Dispatchers.Main) { onResult(r) }
    }

    /** Replaces everything with [d]: saves forge.before-import.json, writes atomically, then reloads from disk. */
    fun importData(d: AppData) = viewModelScope.launch(Dispatchers.IO) {
        RestAlarm.cancel(getApplication())
        repo.import(d)
    }

    /** Writes the full backup to a file the user picked with the Storage Access Framework. */
    fun exportTo(uri: Uri, onResult: (Boolean) -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        val ok = runCatching {
            getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!.use { it.write(exportJson().toByteArray()) }
        }.isSuccess
        withContext(Dispatchers.Main) { onResult(ok) }
    }

    /** Replaces history with twelve weeks of believable training (see [Demo]) and rebuilds the streak from it. */
    fun loadDemoHistory(today: LocalDate = Weeks.today()) = edit { d ->
        val demo = Demo.build(d.templates, ::exercise, today)
        val streak = Streaks.evaluate(StreakState(pausedWeeks = listOf(demo.pausedWeek)), demo.history, d.settings.weeklyGoal, today)
        d.copy(history = demo.history, streak = streak)
    }

    fun clearHistory() = edit { it.copy(history = emptyList(), streak = StreakState()) }

    /** 14.2 Save: name, bodyweight and units, plus a new photo or its removal. */
    fun saveProfile(name: String, bodyweightKg: Double?, unit: WeightUnit, photo: Bitmap?, removePhoto: Boolean) {
        updateSettings { it.copy(name = name.trim().take(30), bodyweightKg = bodyweightKg, unit = unit) }
        when {
            photo != null -> saveAvatar(photo)
            removePhoto -> removeAvatar()
        }
    }

    /** Writes a cropped photo as a 512×512 JPEG in filesDir, points avatarPath at it and deletes the old file. */
    fun saveAvatar(photo: Bitmap) = viewModelScope.launch(Dispatchers.IO) {
        val out = File(getApplication<Application>().filesDir, "avatar_${System.currentTimeMillis()}.jpg")
        val scaled = if (photo.width == 512 && photo.height == 512) photo else Bitmap.createScaledBitmap(photo, 512, 512, true)
        val ok = runCatching { out.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 90, it) } }.getOrDefault(false)
        if (!ok) { out.delete(); return@launch }
        val old = repo.value.settings.avatarPath
        updateSettings { it.copy(avatarPath = out.absolutePath) }
        old?.takeIf { it != out.absolutePath }?.let { File(it).delete() }
    }

    fun removeAvatar() {
        val old = repo.value.settings.avatarPath ?: return
        updateSettings { it.copy(avatarPath = null) }
        File(old).delete()
    }

    /** Profile → Reset all data: removes the photo and everything else, back to the first-launch screen. */
    fun resetAll() {
        RestAlarm.cancel(getApplication())
        repo.value.settings.avatarPath?.let { File(it).delete() }
        edit { AppData(templates = Library.defaultTemplates.map(Library::withColor), seeded = true) }
    }
}
