package app.forge.data

/**
 * Honest XP (section 13): only training earns it. Derived from history and the streak's record of
 * completed quests and comeback weeks, never stored as a running total, so it can't drift.
 *
 * Finished workout +100 · each record +50 · weekly goal hit +200 · repair quest done +100 ·
 * suggested lighter week taken +50 · comeback week done +150. Nothing for opening the app,
 * unticked sets or streak days on their own.
 */
object Xp {
    enum class Tier(val label: String, val from: Int) { IRON("Iron", 0), STEEL("Steel", 4_000), TITANIUM("Titanium", 10_000), CARBON("Carbon", 20_000), FORGED("Forged", 40_000) }

    const val WORKOUT = 100
    const val RECORD = 50
    const val WEEKLY_GOAL = 200
    const val REPAIR_QUEST = 100
    const val LIGHTER_WEEK = Progression.LIGHTER_WEEK_XP
    const val COMEBACK = 150

    /** 500 XP per level (matches Figma: 3,820 XP is level 8, 4,000 is level 9 and Steel). */
    const val PER_LEVEL = 500

    fun total(history: List<Workout>, weeklyGoal: Int, streak: StreakState = StreakState()): Int {
        val prs = Stats.prsBySession(history).values.sum()
        val goalWeeks = history.filter { it.countsForGoal }.groupBy { Weeks.monday(Weeks.date(it.startedAt)) }.count { it.value.size >= weeklyGoal }
        val lighter = history.sumOf { w -> w.exercises.count { it.lighterWeek } }
        // Every finished session that counts for the goal; a short cardio session doesn't earn the +100.
        return history.count { it.countsForGoal } * WORKOUT + prs * RECORD + goalWeeks * WEEKLY_GOAL + lighter * LIGHTER_WEEK +
            streak.questsDone.distinct().size * REPAIR_QUEST + streak.comebacksDone.distinct().size * COMEBACK
    }

    fun total(d: AppData): Int = total(d.history, d.settings.weeklyGoal, d.streak)

    fun level(xp: Int) = xp / PER_LEVEL + 1
    fun tier(xp: Int) = Tier.entries.last { xp >= it.from }
    fun nextTier(xp: Int) = Tier.entries.firstOrNull { it.from > xp }
    /** XP into the current level, 0 until [PER_LEVEL]. */
    fun intoLevel(xp: Int) = xp % PER_LEVEL
}
