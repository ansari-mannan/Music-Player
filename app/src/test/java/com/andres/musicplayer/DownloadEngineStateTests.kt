package com.andres.musicplayer

import android.content.ContextWrapper
import com.andres.musicplayer.data.local.entity.DownloadState
import com.andres.musicplayer.data.youtube.MetadataResolver
import com.andres.musicplayer.data.youtube.MusicMetadata
import com.andres.musicplayer.data.youtube.StreamResolver
import com.andres.musicplayer.download.DownloadEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What Pause All and Resume All do to a queue that is in several states at once.
 *
 * **These lock in current behaviour rather than changing it.** The reported symptom was
 * that the two buttons behaved as a toggle, un-pausing items the user had paused by hand.
 * Nothing in [DownloadEngine] inverts a state — both operations name the states they move
 * *from* and the single state they move *to* — so there was no toggle to remove, and the
 * likely explanation is a stale build on the device. If it is still reproducible after
 * this lands, a clean uninstall and reinstall is the next thing to try, not another edit
 * here. These tests exist so that if a toggle ever does appear, it fails immediately.
 *
 * One thing did change: Pause All now also moves `DOWNLOADING` rows, which previously
 * relied entirely on a live coroutine being there to cancel. See the last test.
 */
class DownloadEngineStateTests {

    @Test
    fun `pause all pauses everything unfinished, whatever state it was in`() {
        val dao = FakeDownloadDao()
        dao.seed(
            entry("queued", DownloadState.QUEUED),
            entry("running", DownloadState.DOWNLOADING),
            entry("paused", DownloadState.PAUSED),
        )
        val engine = engine(dao)

        runBlocking { engine.pauseAll().join() }

        assertEquals(
            mapOf(
                "queued" to DownloadState.PAUSED,
                "running" to DownloadState.PAUSED,
                "paused" to DownloadState.PAUSED,
            ),
            dao.states(),
        )
    }

    @Test
    fun `resume all queues everything paused, whatever state it was in`() {
        val dao = FakeDownloadDao()
        dao.seed(
            entry("queued", DownloadState.QUEUED),
            entry("running", DownloadState.DOWNLOADING),
            entry("paused", DownloadState.PAUSED),
        )
        val engine = engine(dao)

        runBlocking { engine.resumeAll().join() }

        assertEquals(
            mapOf(
                "queued" to DownloadState.QUEUED,
                // Left alone rather than flipped — a toggle would have paused this one.
                "running" to DownloadState.DOWNLOADING,
                "paused" to DownloadState.QUEUED,
            ),
            dao.states(),
        )
    }

    @Test
    fun `pause all then resume all is not a round trip back to where it started`() {
        // The exact sequence behind the bug report. A hand-paused item must come back
        // with everything else, and nothing may be left paused.
        val dao = FakeDownloadDao()
        dao.seed(
            entry("queued", DownloadState.QUEUED),
            entry("running", DownloadState.DOWNLOADING),
            entry("hand-paused", DownloadState.PAUSED),
        )
        val engine = engine(dao)

        runBlocking {
            engine.pauseAll().join()
            engine.resumeAll().join()
        }

        assertEquals(
            mapOf(
                "queued" to DownloadState.QUEUED,
                "running" to DownloadState.QUEUED,
                "hand-paused" to DownloadState.QUEUED,
            ),
            dao.states(),
        )
    }

    @Test
    fun `pause all twice is the same as once`() {
        // An absolute operation is idempotent; a toggle is the opposite of idempotent,
        // and pressing the button twice is exactly how a user would find out.
        val dao = FakeDownloadDao()
        dao.seed(
            entry("a", DownloadState.QUEUED),
            entry("b", DownloadState.DOWNLOADING),
            entry("c", DownloadState.PAUSED),
        )
        val engine = engine(dao)

        runBlocking {
            engine.pauseAll().join()
            engine.pauseAll().join()
        }

        assertEquals(listOf(DownloadState.PAUSED), dao.states().values.distinct())
    }

    @Test
    fun `resume all twice is the same as once`() {
        val dao = FakeDownloadDao()
        dao.seed(entry("a", DownloadState.PAUSED), entry("b", DownloadState.PAUSED))
        val engine = engine(dao)

        runBlocking {
            engine.resumeAll().join()
            engine.resumeAll().join()
        }

        assertEquals(listOf(DownloadState.QUEUED), dao.states().values.distinct())
    }

    @Test
    fun `neither touches finished or failed history`() {
        val dao = FakeDownloadDao()
        dao.seed(
            entry("done", DownloadState.COMPLETED),
            entry("broken", DownloadState.FAILED),
            entry("queued", DownloadState.QUEUED),
        )
        val engine = engine(dao)

        runBlocking {
            engine.pauseAll().join()
            engine.resumeAll().join()
        }

        assertEquals(DownloadState.COMPLETED, dao.stateOf("done"))
        assertEquals(DownloadState.FAILED, dao.stateOf("broken"))
    }

    @Test
    fun `a downloading row with no live job behind it is still paused`() {
        // The case that used to slip through: the DB says DOWNLOADING but the coroutine
        // died with the process, so there was nothing to cancel and nothing moved the
        // row. It would sit there claiming to be downloading until the next app start.
        val dao = FakeDownloadDao()
        dao.seed(entry("orphan", DownloadState.DOWNLOADING))
        val engine = engine(dao)

        runBlocking { engine.pauseAll().join() }

        assertEquals(DownloadState.PAUSED, dao.stateOf("orphan"))
    }

    /**
     * The engine with every side door closed: no foreground service, no metadata lookup,
     * and a fake DAO that hands out no work, so nothing here reaches the network.
     */
    private fun engine(dao: FakeDownloadDao, concurrency: Int = 2) = DownloadEngine(
        appContext = ContextWrapper(null),
        downloadDao = dao,
        downloadedVideoDao = FakeDownloadedVideoDao(),
        resolver = StreamResolver(),
        concurrency = MutableStateFlow(concurrency),
        watchedFolder = { null },
        metadataResolver = object : MetadataResolver {
            override suspend fun resolve(videoId: String): MusicMetadata? = null
        },
        startService = {},
    )
}
