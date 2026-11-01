package app.forge

import android.content.Context
import androidx.annotation.VisibleForTesting
import app.forge.data.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * The one copy of the app's data per process, and the only way to change it. The view model and the
 * rest notification's actions (section 09) both go through [update], so a set logged from the
 * notification while the app is open lands in the same state the screen shows, and neither side can
 * overwrite the other's change with a stale copy.
 *
 * Writes are atomic (see [ForgeStore.write]) and always save the latest state: changes are batched for
 * 300 ms while the app is in use, and [flush] saves immediately (notification actions, app going to the
 * background) so a process that's killed right after loses nothing.
 */
class ForgeRepository private constructor(private val app: Context) {
    private val store = ForgeStore(app.filesDir)
    private val state = MutableStateFlow(load())
    val data: StateFlow<AppData> = state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Any()
    @Volatile private var written: AppData? = null

    init {
        @OptIn(FlowPreview::class)
        scope.launch { state.drop(1).debounce(300).collect { flush() } }
        // The ongoing notification and the rest alarm follow the session, whoever changed it.
        scope.launch {
            state.map { it.active to it.settings }.distinctUntilChanged().collect { RestNotifier.sync(app, state.value) }
        }
    }

    val value: AppData get() = state.value

    /** Applies [f] atomically (retried if another change landed first; [f] must be pure). */
    fun update(f: (AppData) -> AppData): AppData = state.updateAndGet(f)

    /** Saves the latest state now, if it hasn't been saved already. Safe from any thread. */
    fun flush() = synchronized(writeLock) {
        val d = state.value
        if (d !== written) { store.write(d); written = d }
    }

    fun flushInBackground() { scope.launch { flush() } }

    fun encode(d: AppData) = store.encode(d)
    fun decodeBackup(text: String) = store.decodeBackup(text)

    /** Replaces everything with [d]: keeps forge.before-import.json, writes atomically, then reloads from disk. */
    fun import(d: AppData) = synchronized(writeLock) {
        store.backupBeforeImport()
        store.write(d.copy(seeded = true))
        val loaded = load()
        state.value = loaded
        written = loaded
    }

    private fun load(): AppData {
        val stored = store.read()
        val d = if (stored == null || !stored.seeded) AppData(templates = Library.defaultTemplates, seeded = true) else stored
        // Templates from before colours existed get one guessed from their name; entries from before
        // tracking types get their type (see Migrate).
        val withColors = Migrate.run(d.copy(templates = d.templates.map(Library::withColor)))
        // Streak rules run on the first open of each new week. Files from before streaks existed have
        // no evaluatedThrough, so their whole history is replayed once to rebuild the streak.
        return withColors.copy(streak = Streaks.evaluate(withColors.streak, withColors.history, withColors.settings.weeklyGoal, Weeks.today()))
    }

    companion object {
        @Volatile private var instance: ForgeRepository? = null

        fun get(ctx: Context): ForgeRepository {
            val app = ctx.applicationContext
            instance?.takeIf { it.app === app }?.let { return it }
            return synchronized(this) {
                instance?.takeIf { it.app === app } ?: ForgeRepository(app).also { instance?.scope?.cancel(); instance = it }
            }
        }

        /** Forgets the in-memory copy, as if the process had been killed. Pending batched writes are dropped. */
        @VisibleForTesting
        fun reset() = synchronized(this) {
            instance?.scope?.cancel()
            instance = null
        }
    }
}
