package app.forge.data

/**
 * Cosmetic rewards (13.2), each tied to a real milestone. Nothing here changes training. What's
 * unlocked is derived from the data, never stored, so it can't disagree with the history.
 */
object Unlocks {
    /** Lifetime numbers the milestones are measured against. */
    data class Progress(val level: Int, val pushDays: Int, val bestStreakWeeks: Int, val sessions: Int, val lifetimeKg: Double)

    fun progress(d: AppData): Progress {
        val xp = Xp.total(d)
        val colors = d.templates.associate { it.id to it.colorKey }
        val push = d.history.count { w -> (w.templateId?.let { colors[it] } ?: Library.colorKeyFor(w.name)) == "push" }
        return Progress(Xp.level(xp), push, maxOf(d.streak.bestStreakWeeks, d.streak.weeks), d.history.size, d.history.sumOf { Stats.volume(it) })
    }

    /** [requirement] is the caption under a locked item ("LEVEL 9"); [fg] is the bar and plate, [accent] the newest plate. */
    enum class Icon(val key: String, val label: String, val alias: String, val requirement: String, val bg: Long, val fg: Long, val accent: Long, val met: (Progress) -> Boolean) {
        LIME("lime", "Lime", "MainActivity", "", 0xFFCCFF00, 0xFF0E0D0B, 0xFF0E0D0B, { true }),
        INK("ink", "Ink", "IconInk", "", 0xFF0E0D0B, 0xFFEEEAE2, 0xFFCCFF00, { true }),
        PAPER("paper", "Paper", "IconPaper", "Level 9", 0xFFEEEAE2, 0xFF0E0D0B, 0xFF0E0D0B, { it.level >= 9 }),
        EMBER("ember", "Ember", "IconEmber", "100 push days", 0xFFFF6A3D, 0xFF0E0D0B, 0xFF0E0D0B, { it.pushDays >= 100 }),
        COBALT("cobalt", "Cobalt", "IconCobalt", "Level 12", 0xFF4D7CFF, 0xFF0E0D0B, 0xFF0E0D0B, { it.level >= 12 }),
        FOIL("foil", "Foil", "IconFoil", "52-wk streak", 0xFFE8C872, 0xFF0E0D0B, 0xFF0E0D0B, { it.bestStreakWeeks >= 52 });

        companion object { fun of(key: String) = entries.firstOrNull { it.key == key } ?: LIME }
    }

    /** How History draws spines (only that: the shelf itself doesn't change). */
    enum class Spine(val key: String, val label: String, val requirement: String, val met: (Progress) -> Boolean) {
        SOLID("solid", "Solid", "", { true }),
        LINEN("linen", "Linen", "Level 9", { it.level >= 9 }),
        BOUND("bound", "Bound", "100 sessions", { it.sessions >= 100 }),
        FOIL("foil", "Foil", "1M kg", { it.lifetimeKg >= 1_000_000 });

        companion object { fun of(key: String) = entries.firstOrNull { it.key == key } ?: SOLID }
    }

    fun icons(p: Progress) = Icon.entries.filter { it.met(p) }
    fun spines(p: Progress) = Spine.entries.filter { it.met(p) }

    /** Rewards that [after] has and [before] didn't: the 13.3 "Unlocked" list. */
    data class New(val icons: List<Icon>, val spines: List<Spine>)

    fun newlyUnlocked(before: Progress, after: Progress) =
        New(icons(after) - icons(before).toSet(), spines(after) - spines(before).toSet())
}
