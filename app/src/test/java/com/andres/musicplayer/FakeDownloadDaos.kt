package com.andres.musicplayer

import com.andres.musicplayer.data.local.dao.DownloadDao
import com.andres.musicplayer.data.local.dao.DownloadedVideoDao
import com.andres.musicplayer.data.local.entity.DownloadEntry
import com.andres.musicplayer.data.local.entity.DownloadEntryWithSource
import com.andres.musicplayer.data.local.entity.DownloadState
import com.andres.musicplayer.data.local.entity.DownloadedVideo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * An in-memory stand-in for the download queue, so the engine's own state machine can be
 * exercised on the JVM instead of only ever on a phone.
 *
 * ### It deliberately hands out no work
 *
 * [nextInState] always returns nothing, however many rows are queued. That is not an
 * oversight: `pump()` would otherwise start real coroutines that resolve real videos and
 * open real sockets, in a unit test. What is under test here is which rows end up in
 * which state, and the requests the pool makes for work — [dispatchRequests] records the
 * limit asked for on every pump, which is the observable half of "the pool noticed".
 */
class FakeDownloadDao : DownloadDao {

    private val rows = LinkedHashMap<String, DownloadEntry>()
    private val revision = MutableStateFlow(0)

    /** The `limit` argument of every [nextInState] call, oldest first. */
    val dispatchRequests = mutableListOf<Int>()

    fun seed(vararg entries: DownloadEntry) {
        entries.forEach { rows[it.id] = it }
        bump()
    }

    fun stateOf(id: String): DownloadState? = rows[id]?.state

    /** Every row's state, keyed by id — the whole picture, for one assertion. */
    fun states(): Map<String, DownloadState> = rows.mapValues { it.value.state }

    private fun bump() { revision.value++ }

    private fun update(id: String, change: (DownloadEntry) -> DownloadEntry) {
        rows[id]?.let { rows[id] = change(it) }
        bump()
    }

    // --- Reads ------------------------------------------------------------------------

    override fun entriesInStates(states: List<DownloadState>): Flow<List<DownloadEntryWithSource>> =
        revision.map { snapshot(states) }

    override fun entriesInState(state: DownloadState): Flow<List<DownloadEntryWithSource>> =
        revision.map { snapshot(listOf(state)) }

    override fun countInStates(states: List<DownloadState>): Flow<Int> =
        revision.map { snapshot(states).size }

    private fun snapshot(states: List<DownloadState>) = rows.values
        .filter { it.state in states }
        .map { DownloadEntryWithSource(it, playlistTitle = null) }

    override suspend fun getById(id: String): DownloadEntry? = rows[id]

    override suspend fun getByIdWithSource(id: String): DownloadEntryWithSource? =
        rows[id]?.let { DownloadEntryWithSource(it, playlistTitle = null) }

    override suspend fun nextInState(state: DownloadState, limit: Int): List<DownloadEntry> {
        dispatchRequests += limit
        return emptyList()
    }

    override suspend fun listInStates(states: List<DownloadState>): List<DownloadEntry> =
        rows.values.filter { it.state in states }

    override suspend fun allVideoIds(): List<String> = rows.values.map { it.videoId }

    // --- Writes -----------------------------------------------------------------------

    override suspend fun upsert(entry: DownloadEntry) {
        rows[entry.id] = entry
        bump()
    }

    override suspend fun upsertAll(entries: List<DownloadEntry>) {
        entries.forEach { rows[it.id] = it }
        bump()
    }

    override suspend fun updateProgress(id: String, bytesDownloaded: Long, totalBytes: Long?) =
        update(id) { it.copy(bytesDownloaded = bytesDownloaded, totalBytes = totalBytes) }

    override suspend fun updateState(id: String, state: DownloadState) =
        update(id) { it.copy(state = state) }

    override suspend fun updateTitle(id: String, title: String) =
        update(id) { it.copy(title = title) }

    override suspend fun setHasEmbeddedArt(id: String, hasArt: Boolean) =
        update(id) { it.copy(hasEmbeddedArt = hasArt) }

    override suspend fun updateStateAndProgress(
        id: String,
        state: DownloadState,
        bytesDownloaded: Long,
    ) = update(id) { it.copy(state = state, bytesDownloaded = bytesDownloaded) }

    override suspend fun setFailure(id: String, reason: String, state: DownloadState) =
        update(id) { it.copy(state = state, failureReason = reason) }

    override suspend fun setCompletion(id: String, completedAt: Long, state: DownloadState) =
        update(id) { it.copy(state = state, completedAt = completedAt, failureReason = null) }

    override suspend fun transitionAll(from: List<DownloadState>, to: DownloadState) {
        rows.keys.toList().forEach { id ->
            rows[id]?.takeIf { it.state in from }?.let {
                rows[id] = it.copy(state = to, failureReason = null)
            }
        }
        bump()
    }

    override suspend fun requeue(id: String, to: DownloadState) =
        update(id) { it.copy(state = to, failureReason = null) }

    override suspend fun deleteById(id: String) {
        rows.remove(id)
        bump()
    }

    override suspend fun deleteInState(state: DownloadState) {
        rows.values.removeAll { it.state == state }
        bump()
    }

    override suspend fun deletePendingForUnwatched(
        watchedPlaylistIds: List<String>,
        pendingStates: List<DownloadState>,
    ) {
        rows.values.removeAll {
            it.state in pendingStates &&
                it.sourcePlaylistId != null &&
                it.sourcePlaylistId !in watchedPlaylistIds
        }
        bump()
    }

    override suspend fun deleteAllPendingFromPlaylists(pendingStates: List<DownloadState>) {
        rows.values.removeAll { it.state in pendingStates && it.sourcePlaylistId != null }
        bump()
    }
}

/** Nothing has been downloaded; the engine only asks this whether it already has a video. */
class FakeDownloadedVideoDao : DownloadedVideoDao {
    private val rows = LinkedHashMap<String, DownloadedVideo>()
    private val revision = MutableStateFlow(0)

    override suspend fun insert(video: DownloadedVideo) {
        rows[video.videoId] = video
        revision.value++
    }

    override suspend fun allVideoIds(): List<String> = rows.keys.toList()

    override suspend fun getById(videoId: String): DownloadedVideo? = rows[videoId]

    override fun getAll(): Flow<List<DownloadedVideo>> = revision.map { rows.values.toList() }

    override suspend fun deleteById(videoId: String) {
        rows.remove(videoId)
        revision.value++
    }
}

/** Builds a queue row with only the fields these tests care about. */
fun entry(id: String, state: DownloadState) = DownloadEntry(
    id = id,
    videoId = id,
    title = "Song $id",
    thumbnailUrl = "",
    state = state,
)
