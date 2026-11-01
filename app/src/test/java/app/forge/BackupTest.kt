package app.forge

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.forge.data.WeightUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before fun clean() {
        listOf("forge.json", "forge.json.tmp", "forge.before-import.json").forEach { app.filesDir.resolve(it).delete() }
        ForgeRepository.reset()
    }

    @Test fun exportImportRoundTripIsIdentical() = runBlocking {
        val vm = AppViewModel(app)
        vm.loadDemoHistory()
        vm.updateSettings { it.copy(name = "Round Trip", unit = WeightUnit.LB, restSeconds = 75) }
        vm.startWorkout(vm.data.value.templates.first())
        vm.setWeight(0, 0, 22.5); vm.setReps(0, 0, 9)
        val before = vm.data.value

        val exported = vm.exportJson()
        vm.clearHistory(); vm.cancelWorkout()
        Thread.sleep(1000) // let the debounced save write the cleared state to forge.json
        val replaced = vm.data.value
        vm.importData(vm.parseBackup(exported).getOrThrow()).join()

        assertEquals(before, vm.data.value)
        ForgeRepository.reset() // a fresh process reads the file
        assertEquals(before, AppViewModel(app).data.value) // and it's what's on disk
        // The data that was replaced is kept for undo.
        val undo = app.filesDir.resolve("forge.before-import.json").readText()
        assertEquals(replaced, vm.parseBackup(undo).getOrThrow())
    }

    @Test fun badFilesAreRejected() {
        val vm = AppViewModel(app)
        assertTrue(vm.parseBackup("not json").isFailure)
        assertTrue(vm.parseBackup("{}").isFailure)
        assertTrue(vm.parseBackup("""{"history": "oops"}""").isFailure)
        assertTrue(vm.parseBackup("[1,2]").isFailure)
    }
}
