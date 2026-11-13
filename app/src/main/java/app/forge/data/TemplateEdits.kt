package app.forge.data

/** Pure changes to the template list, so the view model only decides when to apply them. */
object TemplateEdits {
    /** Replaces the template with the same id, or appends it. */
    fun save(d: AppData, t: Template): AppData =
        if (d.templates.any { it.id == t.id }) d.copy(templates = d.templates.map { if (it.id == t.id) t else it })
        else d.copy(templates = d.templates + t)

    fun delete(d: AppData, id: Long): AppData = d.copy(templates = d.templates.filterNot { it.id == id })

    fun duplicate(d: AppData, t: Template, newId: Long): AppData =
        d.copy(templates = d.templates + t.copy(id = newId, name = "${t.name} copy"))

    /** 5.4: applies the proposed changes (see [TemplateDiff]) to the workout's template. */
    fun updateFrom(d: AppData, w: Workout): AppData =
        d.copy(templates = d.templates.map { t -> if (t.id != w.templateId) t else TemplateDiff.apply(t, TemplateDiff.changes(t, w)) })

    /** 6.2: a template shaped like the session: same exercises and set counts, top-set reps. */
    fun fromWorkout(w: Workout, id: Long): Template = Library.withColor(Template(id, w.name, w.exercises.map { e ->
        TemplateExercise(e.exerciseId, e.sets.size, Stats.topSet(e)?.reps ?: 8)
    }))

    /** 7.3 balance fix: appends an exercise to a template. */
    fun addExercise(d: AppData, templateId: Long, exerciseId: String): AppData =
        d.copy(templates = d.templates.map { t -> if (t.id == templateId) t.copy(exercises = t.exercises + TemplateExercise.fresh(exerciseId)) else t })
}
