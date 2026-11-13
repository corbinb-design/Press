package app.forge

import app.forge.data.*
import org.junit.Assert.assertEquals
import org.junit.Test

class TemplateEditsTest {
    private fun t(id: Long, name: String) = Template(id, name, listOf(TemplateExercise("bench", 3, 8)))
    private val base = AppData(templates = listOf(t(1, "Push"), t(2, "Pull")))

    @Test fun saveReplacesSameIdAndAppendsNew() {
        val replaced = TemplateEdits.save(base, t(1, "Push B"))
        assertEquals(listOf("Push B", "Pull"), replaced.templates.map { it.name })
        assertEquals(3, TemplateEdits.save(base, t(3, "Legs")).templates.size)
    }

    @Test fun deleteRemovesOnlyThatTemplate() {
        assertEquals(listOf(2L), TemplateEdits.delete(base, 1).templates.map { it.id })
    }

    @Test fun duplicateAddsCopyWithNewId() {
        val d = TemplateEdits.duplicate(base, base.templates[0], 9)
        assertEquals(9L, d.templates.last().id)
        assertEquals("Push copy", d.templates.last().name)
    }

    @Test fun addExerciseAppendsToOneTemplate() {
        val d = TemplateEdits.addExercise(base, 2, "row")
        assertEquals(1, d.templates[0].exercises.size)
        assertEquals(listOf("bench", "row"), d.templates[1].exercises.map { it.exerciseId })
    }
}
