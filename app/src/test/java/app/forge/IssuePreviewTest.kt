package app.forge

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.forge.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Profile → Preview issue: written now (template here, offline), and nothing saved. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IssuePreviewTest {
    @Test fun previewChangesNothingAndSaysWhyItFellBack() = runBlocking {
        Gemini.offline = true
        val app: Application = ApplicationProvider.getApplicationContext()
        app.filesDir.listFiles()?.forEach { it.delete() }
        ForgeRepository.reset()
        val vm = AppViewModel(app).apply { completeSetup("Corbin", WeightUnit.KG, 4, Library.splits.first()); loadDemoHistory() }
        val before = vm.data.value
        val monday = Weeks.monday(Weeks.today()).minusWeeks(1)
        val (text, why) = vm.previewIssue(Weeks.key(monday))
        assertFalse(text.fromAi)
        assertEquals(Issues.issue(before, monday).headline, text.headline)
        assertEquals("No Gemini key in this build", why)
        assertSame("archive, cache and settings untouched", before, vm.data.value)
    }
}
