package com.andres.musicplayer

import android.content.ContextWrapper
import android.content.SharedPreferences
import com.andres.musicplayer.data.local.entity.DownloadState
import com.andres.musicplayer.data.prefs.DownloadPreferences
import com.andres.musicplayer.data.youtube.MetadataResolver
import com.andres.musicplayer.data.youtube.MusicMetadata
import com.andres.musicplayer.data.youtube.StreamResolver
import com.andres.musicplayer.download.DownloadEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Changing "simultaneous downloads" while downloads are running.
 *
 * The reported symptom was that the setting did nothing until the app was restarted, and
 * the engine did look innocent: it collects a `StateFlow` and pumps the pool on every
 * emission. The flow was the problem. Every `DownloadPreferences` instance built its own
 * `MutableStateFlow` from disk, so the picker updated the screen's copy and the engine
 * kept watching a copy that nothing would ever write to again. A restart "fixed" it
 * because a restart is when the engine re-read the disk.
 *
 * The first test below is the fix. The rest pin the pool behaviour that was correct all
 * along, so a future change can't quietly undo it.
 */
class DownloadEngineConcurrencyTest {

    @Test
    fun `a change made on one preferences handle reaches every other one`() {
        // Exactly the shape of the real thing: the picker in Settings, the picker in the
        // Downloads tab, and the engine each hold their own handle.
        val context = FakeContext()
        val settingsScreen = DownloadPreferences(context)
        val downloadsTab = DownloadPreferences(context)
        val engineSide = DownloadPreferences(context)

        settingsScreen.setConcurrency(3)
        assertEquals(3, downloadsTab.concurrency.value)
        assertEquals("the engine's view is the one that used to go stale", 3, engineSide.concurrency.value)

        downloadsTab.setConcurrency(1)
        assertEquals(1, settingsScreen.concurrency.value)
        assertEquals(1, engineSide.concurrency.value)
    }

    @Test
    fun `the value is clamped no matter which handle sets it`() {
        val context = FakeContext()
        val prefs = DownloadPreferences(context)

        prefs.setConcurrency(99)
        assertEquals(DownloadPreferences.MAX, DownloadPreferences(context).concurrency.value)

        prefs.setConcurrency(0)
        assertEquals(DownloadPreferences.MIN, DownloadPreferences(context).concurrency.value)
    }

    @Test
    fun `the pool re-pumps on every change the engine sees`() {
        val dao = FakeDownloadDao()
        dao.seed(
            entry("a", DownloadState.QUEUED),
            entry("b", DownloadState.QUEUED),
            entry("c", DownloadState.QUEUED),
        )
        val concurrency = MutableStateFlow(1)
        engine(dao, concurrency)

        // The flow's initial value pumps once, as soon as the collector is running.
        awaitDispatches(dao, atLeast = 1)
        assertEquals(listOf(1), dao.dispatchRequests.toList())

        // One at a time, waiting between: a StateFlow is conflated, so writing 2 and 3
        // back to back is allowed to deliver only 3 — the guarantee is about the values
        // the engine observes, and it is why this asserts on values and not on a count.
        concurrency.value = 2
        awaitDispatches(dao, atLeast = 2)
        concurrency.value = 3
        awaitDispatches(dao, atLeast = 3)

        assertEquals(listOf(1, 2, 3), dao.dispatchRequests.toList())
    }

    @Test
    fun `raising the limit asks for the newly-available slots straight away`() {
        // With nothing running, the free slots are the whole limit — so the request the
        // pool makes is the new value, not the value it started with.
        val dao = FakeDownloadDao()
        dao.seed(
            entry("a", DownloadState.QUEUED),
            entry("b", DownloadState.QUEUED),
            entry("c", DownloadState.QUEUED),
        )
        val concurrency = MutableStateFlow(1)
        engine(dao, concurrency)
        awaitDispatches(dao, atLeast = 1)

        concurrency.value = 3
        awaitDispatches(dao, atLeast = 2)

        assertEquals(
            "the pool must ask using the live limit, not the one it was built with",
            3,
            dao.dispatchRequests.last(),
        )
    }

    @Test
    fun `lowering the limit never cuts off work already running`() {
        // Deliberate, and the reason the Settings copy says so: the engine only limits
        // what starts next. A queued row must not be pushed back to paused either.
        val dao = FakeDownloadDao()
        dao.seed(entry("a", DownloadState.QUEUED), entry("b", DownloadState.DOWNLOADING))
        val concurrency = MutableStateFlow(3)
        engine(dao, concurrency)
        awaitDispatches(dao, atLeast = 1)

        concurrency.value = 1
        awaitDispatches(dao, atLeast = 2)

        assertEquals(
            mapOf("a" to DownloadState.QUEUED, "b" to DownloadState.DOWNLOADING),
            dao.states(),
        )
    }

    /** The collector runs on Dispatchers.IO, so the pump lands a moment after the write. */
    private fun awaitDispatches(dao: FakeDownloadDao, atLeast: Int) = runBlocking {
        val deadline = System.currentTimeMillis() + 2_000
        while (dao.dispatchRequests.size < atLeast && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(10)
        }
    }

    private fun engine(dao: FakeDownloadDao, concurrency: MutableStateFlow<Int>) = DownloadEngine(
        appContext = ContextWrapper(null),
        downloadDao = dao,
        downloadedVideoDao = FakeDownloadedVideoDao(),
        resolver = StreamResolver(),
        concurrency = concurrency,
        watchedFolder = { null },
        metadataResolver = object : MetadataResolver {
            override suspend fun resolve(videoId: String): MusicMetadata? = null
        },
        startService = {},
    )
}

/**
 * A context whose only job is to hand out a working [SharedPreferences]. The stub
 * android.jar has no implementation of its own, and the point of the first test is that
 * several `DownloadPreferences` built from the same context agree with each other.
 */
private class FakeContext : ContextWrapper(null) {
    private val store = FakeSharedPreferences()
    override fun getApplicationContext() = this
    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = store
}

private class FakeSharedPreferences : SharedPreferences {
    private val values = HashMap<String, Any?>()

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun getString(key: String?, defValue: String?) = values[key] as? String ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?) =
        @Suppress("UNCHECKED_CAST") (values[key] as? MutableSet<String> ?: defValues)
    override fun getInt(key: String?, defValue: Int) = values[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long) = values[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float) = values[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean) = values[key] as? Boolean ?: defValue
    override fun contains(key: String?) = values.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = HashMap<String, Any?>()
        private var clearFirst = false

        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, value: MutableSet<String>?) =
            apply { pending[key] = value }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun remove(key: String) = apply { pending[key] = REMOVED }
        override fun clear() = apply { clearFirst = true }

        override fun commit(): Boolean {
            if (clearFirst) values.clear()
            pending.forEach { (key, value) ->
                if (value === REMOVED) values.remove(key) else values[key] = value
            }
            return true
        }

        override fun apply() { commit() }
    }

    private companion object {
        /** Distinguishes "remove this key" from "store a null". */
        val REMOVED = Any()
    }
}
