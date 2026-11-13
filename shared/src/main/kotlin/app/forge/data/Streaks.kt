package app.forge.data

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.temporal.IsoFields

/** Persisted streak state (section 02). Every field has a default so older forge.json files load. */
@Serializable
data class StreakState(
    /** Consecutive weeks that counted, not including the week in progress. */
    val weeks: Int = 0,
    val bestStreakWeeks: Int = 0,
    /** Banked freezes, 0..[Streaks.MAX_FREEZES]. */
    val freezes: Int = 0,
    /** Completed weeks in a row since the last freeze was earned or the run broke (0..3). */
    val towardFreeze: Int = 0,
    /** ISO week keys ("2026-W42") skipped entirely: no freeze used, streak untouched. */
    val pausedWeeks: List<String> = emptyList(),
    /** Set while paused "until I resume": the first paused week. Weeks are added to [pausedWeeks] as they pass. */
    val pausedFrom: String? = null,
    val pauseReason: String? = null,
    val repair: RepairQuest? = null,
    /** The week whose target is 2 after a reset. */
    val comebackWeek: String? = null,
    /** Last week already evaluated; weeks after it are evaluated on the next open. */
    val evaluatedThrough: String? = null,
    /** What to explain on Home (sheets 2.1–2.3), cleared once seen. */
    val notice: StreakNotice? = null,
    /** Weeks whose repair quest was completed, and comeback weeks that hit their target (XP, section 13). */
    val questsDone: List<String> = emptyList(),
    val comebacksDone: List<String> = emptyList(),
    /** Context layer: weeks with 2+ outside sessions totalling 3h+, which meet the weekly goal (see [Outside.activeWeek]). */
    val outsideWeeks: List<String> = emptyList(),
)

/** Rule 3: goal + 1 sessions in [week] and the missed week counts after all. */
@Serializable
data class RepairQuest(val week: String, val target: Int, val streakAtStake: Int, val missedSessions: Int, val accepted: Boolean = false)

@Serializable
data class StreakNotice(val kind: Kind, val week: String, val sessions: Int = 0, val goal: Int = 0) {
    enum class Kind { FREEZE_USED, REPAIR_OFFERED, FRESH_START }
}

/**
 * Weekly-goal streak rules, evaluated on the first open of a new week (Monday–Sunday, local time).
 * Everything here is pure: callers pass history, the goal and today's date.
 *
 * 1. Last week hit the goal: streak +1. Every 4 completed weeks in a row earns a freeze (bank max 2).
 * 2. Missed with a freeze banked: it's used automatically and the streak is kept (sheet 2.1).
 * 3. Missed with no freeze: a repair quest for this week, goal + 1 sessions (sheet 2.2). Done in time,
 *    last week counts too (streak +2).
 * 4. Quest failed or declined: the streak resets to 0 (best kept) and the next week's target is 2, a
 *    comeback week (sheet 2.3). If the failed quest week still met the normal goal, that week counts as 1.
 * 5. Paused weeks (2.4: 1 week, 2 weeks or until resumed; at most 4 a year) are skipped entirely.
 * 6. A week active outside the gym ([StreakState.outsideWeeks]) meets the weekly goal, whatever was logged.
 * A missed week with no streak to protect changes nothing.
 */
object Streaks {
    const val MAX_FREEZES = 2
    const val WEEKS_PER_FREEZE = 4
    const val MAX_PAUSED_PER_YEAR = 4
    const val COMEBACK_TARGET = 2

    fun key(d: LocalDate): String = Weeks.key(d)

    /** Monday of an ISO week key. */
    fun monday(key: String): LocalDate {
        val (y, w) = key.split("-W").map(String::toInt)
        return LocalDate.of(y, 1, 4).with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, w.toLong()).with(java.time.DayOfWeek.MONDAY)
    }

    private fun year(key: String) = key.substringBefore("-W")

    fun pausedInYear(s: StreakState, year: String) = s.pausedWeeks.count { year(it) == year }

    fun isPaused(s: StreakState, key: String) = key in s.pausedWeeks || (s.pausedFrom != null && key >= s.pausedFrom)

    /** This week's target: 0 while paused, goal + 1 during a repair quest, 2 in a comeback week, else the goal. */
    fun target(s: StreakState, goal: Int, today: LocalDate): Int {
        val k = key(today)
        return when {
            isPaused(s, k) -> 0
            s.repair?.week == k -> s.repair.target
            s.comebackWeek == k -> COMEBACK_TARGET
            else -> goal
        }
    }

    /** The streak to show: completed weeks, plus this week once its target is met. */
    fun display(s: StreakState, history: List<Workout>, goal: Int, today: LocalDate): Int {
        val t = target(s, goal, today)
        val logged = Weeks.sessionsIn(history, Weeks.monday(today)).size
        val thisWeek = if (key(today) in s.outsideWeeks) maxOf(logged, goal) else logged
        if (t == 0) return if (thisWeek >= goal) s.weeks + 1 else s.weeks // paused, but trained anyway
        return if (thisWeek >= t) (if (s.repair?.week == key(today)) s.repair.streakAtStake + 2 else s.weeks + 1) else s.weeks
    }

    /**
     * Evaluates every finished week not yet evaluated, oldest first. Only the most recent week's
     * notice survives, so a late open after weeks away shows one sheet, not a pile.
     */
    fun evaluate(s: StreakState, history: List<Workout>, goal: Int, today: LocalDate): StreakState {
        val lastWeek = Weeks.monday(today).minusWeeks(1)
        val start = s.evaluatedThrough?.let { monday(it).plusWeeks(1) }
            ?: history.minOfOrNull { it.startedAt }?.let { Weeks.monday(Weeks.date(it)) }
            ?: return s.copy(evaluatedThrough = key(lastWeek))
        var st = s
        var w = start
        while (!w.isAfter(lastWeek)) {
            st = week(st.copy(notice = null), w, Weeks.sessionsIn(history, w).size, goal)
            w = w.plusWeeks(1)
        }
        // Silent backfill: the first evaluation of a file from before streaks existed replays history
        // without any sheets. A quest it leaves running is treated as already accepted, so Home shows the
        // quest card but no sheet; sheets start with the first new week after the update.
        if (s.evaluatedThrough == null) st = st.copy(notice = null, repair = st.repair?.copy(accepted = true))
        return st.copy(evaluatedThrough = maxOf(key(lastWeek), s.evaluatedThrough ?: ""))
    }

    /**
     * Context layer: replays every week from scratch, silently, after the outside weeks change (a fresh Health Connect
     * read or a confirmed note), so a week in the hills is judged again. Pauses are kept; the best streak never drops.
     */
    fun rebuild(s: StreakState, history: List<Workout>, goal: Int, today: LocalDate): StreakState {
        val replay = evaluate(
            StreakState(pausedWeeks = s.pausedWeeks, pausedFrom = s.pausedFrom, pauseReason = s.pauseReason, outsideWeeks = s.outsideWeeks),
            history, goal, today,
        )
        return replay.copy(bestStreakWeeks = maxOf(replay.bestStreakWeeks, s.bestStreakWeeks), notice = null)
    }

    /** Applies the rules to one finished week with [logged] sessions (rule 6: an outside week meets the goal). */
    fun week(s0: StreakState, monday: LocalDate, logged: Int, goal: Int): StreakState {
        val k = key(monday)
        val n = if (k in s0.outsideWeeks) maxOf(logged, goal) else logged
        var s = s0
        // Rule 5: an open-ended pause covers this week unless the yearly allowance has run out.
        if (s.pausedFrom != null && k >= s.pausedFrom && k !in s.pausedWeeks) {
            s = if (pausedInYear(s, year(k)) < MAX_PAUSED_PER_YEAR) s.copy(pausedWeeks = s.pausedWeeks + k)
            else s.copy(pausedFrom = null, pauseReason = null)
        }
        // A pause can never cost a week: training through it still counts if the target is hit, and a
        // quest or comeback that falls in a paused week moves to the next week instead of failing.
        if (k in s.pausedWeeks) {
            val next = key(monday.plusWeeks(1))
            s.repair?.takeIf { it.week == k }?.let { q ->
                return if (n >= q.target) completed(s.copy(repair = null, weeks = q.streakAtStake, questsDone = s.questsDone + k), count = 2) else s.copy(repair = q.copy(week = next))
            }
            if (s.comebackWeek == k) return if (n >= COMEBACK_TARGET) completed(s.copy(comebackWeek = null, comebacksDone = s.comebacksDone + k), count = 1) else s.copy(comebackWeek = next)
            return if (n >= goal) completed(s, count = 1) else s
        }

        s.repair?.takeIf { it.week == k }?.let { q ->
            return if (n >= q.target) {
                completed(s.copy(repair = null, weeks = q.streakAtStake, questsDone = s.questsDone + k), count = 2)
            } else {
                // Rule 4: quest failed. If this week still met the normal goal it starts the new streak.
                val reset = s.copy(repair = null, weeks = 0, towardFreeze = 0)
                if (n >= goal) completed(reset, count = 1).copy(notice = StreakNotice(StreakNotice.Kind.FRESH_START, k, n, goal))
                else reset.copy(comebackWeek = key(monday.plusWeeks(1)), notice = StreakNotice(StreakNotice.Kind.FRESH_START, k, n, goal))
            }
        }
        if (s.comebackWeek == k) {
            val cleared = s.copy(comebackWeek = null)
            return if (n >= COMEBACK_TARGET) completed(cleared.copy(comebacksDone = s.comebacksDone + k), count = 1) else cleared
        }
        if (n >= goal) return completed(s, count = 1)

        // Missed.
        val broken = s.copy(towardFreeze = 0)
        return when {
            s.weeks == 0 -> broken
            s.freezes > 0 -> broken.copy(freezes = s.freezes - 1, notice = StreakNotice(StreakNotice.Kind.FREEZE_USED, k, n, goal))
            else -> broken.copy(
                repair = RepairQuest(key(monday.plusWeeks(1)), goal + 1, s.weeks, n),
                notice = StreakNotice(StreakNotice.Kind.REPAIR_OFFERED, k, n, goal),
            )
        }
    }

    /** [count] weeks counted: streak and best go up, and every 4th completed week in a row banks a freeze. */
    private fun completed(s: StreakState, count: Int): StreakState {
        var st = s
        repeat(count) {
            val toward = st.towardFreeze + 1
            st = if (toward >= WEEKS_PER_FREEZE) st.copy(weeks = st.weeks + 1, towardFreeze = 0, freezes = minOf(MAX_FREEZES, st.freezes + 1))
            else st.copy(weeks = st.weeks + 1, towardFreeze = toward)
        }
        return st.copy(bestStreakWeeks = maxOf(st.bestStreakWeeks, st.weeks))
    }

    // ---- User actions -------------------------------------------------------------------------

    fun acceptRepair(s: StreakState) = s.copy(repair = s.repair?.copy(accepted = true), notice = null)

    /** Rule 4 by choice: reset now and make this week a comeback week. */
    fun declineRepair(s: StreakState, today: LocalDate) =
        s.copy(repair = null, weeks = 0, towardFreeze = 0, comebackWeek = key(today), notice = StreakNotice(StreakNotice.Kind.FRESH_START, key(today)))

    enum class PauseLength(val weeks: Int?) { ONE(1), TWO(2), UNTIL_RESUMED(null) }

    /** Paused weeks still available in the year of [today]. */
    fun pausesLeft(s: StreakState, today: LocalDate) = MAX_PAUSED_PER_YEAR - pausedInYear(s, year(key(today)))

    /** Rule 5: pauses start with the current week. Fixed lengths are capped by what's left this year. */
    fun pause(s: StreakState, length: PauseLength, reason: String?, today: LocalDate): StreakState {
        val left = pausesLeft(s, today)
        if (left <= 0) return s
        val thisWeek = key(today)
        return when (val n = length.weeks) {
            null -> s.copy(pausedFrom = thisWeek, pauseReason = reason)
            else -> s.copy(
                pausedWeeks = (s.pausedWeeks + (0 until minOf(n, left)).map { key(Weeks.monday(today).plusWeeks(it.toLong())) }).distinct(),
                pauseReason = reason,
            )
        }
    }

    /** Ends a pause: this week and later count normally again; weeks already past stay paused. */
    fun resume(s: StreakState, today: LocalDate): StreakState {
        val thisWeek = key(today)
        return s.copy(pausedFrom = null, pauseReason = null, pausedWeeks = s.pausedWeeks.filter { it < thisWeek })
    }

    fun pausedNow(s: StreakState, today: LocalDate) = isPaused(s, key(today))

    /** Weeks until the next freeze is earned (for sheet 2.1). */
    fun weeksToNextFreeze(s: StreakState) = WEEKS_PER_FREEZE - s.towardFreeze
}
