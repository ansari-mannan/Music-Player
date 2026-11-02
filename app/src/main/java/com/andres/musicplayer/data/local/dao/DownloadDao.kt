package com.andres.musicplayer.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.andres.musicplayer.data.local.entity.DownloadEntry
import com.andres.musicplayer.data.local.entity.DownloadEntryWithSource
import com.andres.musicplayer.data.local.entity.DownloadState
import kotlinx.coroutines.flow.Flow

/**
 * The download work queue.
 *
 * States are always passed as bound parameters rather than written into the SQL as
 * string literals, so the type converter stays the single place that decides how a
 * [DownloadState] is spelled on disk.
 */
@Dao
interface DownloadDao {

    // --- Reads for the Download Manager UI (Section 4.3). ---

    /**
     * Active section: queued, running and paused work, oldest first (queue order).
     *
     * LEFT JOIN, not INNER: a row whose playlist has been deleted on YouTube must still
     * appear, just without a source name.
     */
    @Query(
        "SELECT download_entries.*, watched_playlists.title AS playlistTitle " +
            "FROM download_entries " +
            "LEFT JOIN watched_playlists " +
            "ON download_entries.sourcePlaylistId = watched_playlists.playlistId " +
            "WHERE download_entries.state IN (:states) " +
            "ORDER BY download_entries.addedAt ASC",
    )
    fun entriesInStates(states: List<DownloadState>): Flow<List<DownloadEntryWithSource>>

    /** Completed section: most recently finished first. */
    @Query(
        "SELECT download_entries.*, watched_playlists.title AS playlistTitle " +
            "FROM download_entries " +
            "LEFT JOIN watched_playlists " +
            "ON download_entries.sourcePlaylistId = watched_playlists.playlistId " +
            "WHERE download_entries.state = :state " +
            "ORDER BY download_entries.completedAt DESC, download_entries.addedAt DESC",
    )
    fun entriesInState(state: DownloadState): Flow<List<DownloadEntryWithSource>>

    @Query("SELECT COUNT(*) FROM download_entries WHERE state IN (:states)")
    fun countInStates(states: List<DownloadState>): Flow<Int>

    // --- Reads for the download service (Section 4.1). ---

    @Query("SELECT * FROM download_entries WHERE id = :id")
    suspend fun getById(id: String): DownloadEntry?

    /**
     * One row together with its playlist name, for tagging a finished file. Same LEFT
     * JOIN as the list queries: a download whose playlist has since been dropped still
     * resolves, just without a name to write into the album field.
     */
    @Query(
        "SELECT download_entries.*, watched_playlists.title AS playlistTitle " +
            "FROM download_entries " +
            "LEFT JOIN watched_playlists " +
            "ON download_entries.sourcePlaylistId = watched_playlists.playlistId " +
            "WHERE download_entries.id = :id",
    )
    suspend fun getByIdWithSource(id: String): DownloadEntryWithSource?

    /** The next slice of work to start, oldest first. */
    @Query(
        "SELECT * FROM download_entries WHERE state = :state " +
            "ORDER BY addedAt ASC LIMIT :limit",
    )
    suspend fun nextInState(state: DownloadState, limit: Int): List<DownloadEntry>

    @Query("SELECT * FROM download_entries WHERE state IN (:states) ORDER BY addedAt ASC")
    suspend fun listInStates(states: List<DownloadState>): List<DownloadEntry>

    /** Video ids already represented in the queue — half of the sync diff (3.3). */
    @Query("SELECT videoId FROM download_entries")
    suspend fun allVideoIds(): List<String>

    // --- Writes. ---

    @Upsert
    suspend fun upsert(entry: DownloadEntry)

    @Upsert
    suspend fun upsertAll(entries: List<DownloadEntry>)

    /**
     * Persisted periodically as bytes arrive, so progress survives a restart.
     * Writes [totalBytes] as given — pass the currently-known value, not null, or a
     * total learned from an earlier response is erased.
     */
    @Query(
        "UPDATE download_entries SET bytesDownloaded = :bytesDownloaded, " +
            "totalBytes = :totalBytes WHERE id = :id",
    )
    suspend fun updateProgress(id: String, bytesDownloaded: Long, totalBytes: Long?)

    @Query("UPDATE download_entries SET state = :state WHERE id = :id")
    suspend fun updateState(id: String, state: DownloadState)

    /** The real title only becomes known once the stream is resolved. */
    @Query("UPDATE download_entries SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: String, title: String)

    /**
     * Records whether the finished file really carries a cover, as read back out of it.
     * Written just before the row is marked completed.
     */
    @Query("UPDATE download_entries SET hasEmbeddedArt = :hasArt WHERE id = :id")
    suspend fun setHasEmbeddedArt(id: String, hasArt: Boolean)

    /** Pausing records the byte offset the next Range request resumes from. */
    @Query(
        "UPDATE download_entries SET state = :state, bytesDownloaded = :bytesDownloaded " +
            "WHERE id = :id",
    )
    suspend fun updateStateAndProgress(id: String, state: DownloadState, bytesDownloaded: Long)

    @Query(
        "UPDATE download_entries SET state = :state, failureReason = :reason WHERE id = :id",
    )
    suspend fun setFailure(id: String, reason: String, state: DownloadState)

    suspend fun markFailed(id: String, reason: String) =
        setFailure(id, reason, DownloadState.FAILED)

    @Query(
        "UPDATE download_entries SET state = :state, completedAt = :completedAt, " +
            "failureReason = NULL WHERE id = :id",
    )
    suspend fun setCompletion(id: String, completedAt: Long, state: DownloadState)

    suspend fun markCompleted(id: String, completedAt: Long = System.currentTimeMillis()) =
        setCompletion(id, completedAt, DownloadState.COMPLETED)

    /**
     * Bulk transition, used by Pause All / Resume All / Retry All Failed. Always clears
     * `failureReason`, which is what all three of those want — a retried or re-queued
     * item must not keep showing the reason it failed last time.
     */
    @Query(
        "UPDATE download_entries SET state = :to, failureReason = NULL " +
            "WHERE state IN (:from)",
    )
    suspend fun transitionAll(from: List<DownloadState>, to: DownloadState)

    /** Single-item retry: back to the queue with last time's failure reason cleared. */
    @Query(
        "UPDATE download_entries SET state = :to, failureReason = NULL WHERE id = :id",
    )
    suspend fun requeue(id: String, to: DownloadState)

    suspend fun requeue(id: String) = requeue(id, DownloadState.QUEUED)

    /** Cancel (Active section) and Remove from list (Completed section). */
    @Query("DELETE FROM download_entries WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM download_entries WHERE state = :state")
    suspend fun deleteInState(state: DownloadState)

    /**
     * Drops not-yet-finished work belonging to playlists the user no longer watches.
     *
     * Only pending states are removed — finished and failed rows are history and stay
     * put. This used to happen implicitly through a cascading foreign key, which also
     * erased that history; doing it explicitly is the whole reason the key was dropped.
     * Hand-started downloads have a null `sourcePlaylistId` and are never touched.
     */
    @Query(
        "DELETE FROM download_entries WHERE state IN (:pendingStates) " +
            "AND sourcePlaylistId IS NOT NULL AND sourcePlaylistId NOT IN (:watchedPlaylistIds)",
    )
    suspend fun deletePendingForUnwatched(
        watchedPlaylistIds: List<String>,
        pendingStates: List<DownloadState>,
    )

    /** Same, for when nothing at all is watched (SQL `NOT IN ()` matches nothing). */
    @Query(
        "DELETE FROM download_entries WHERE state IN (:pendingStates) " +
            "AND sourcePlaylistId IS NOT NULL",
    )
    suspend fun deleteAllPendingFromPlaylists(pendingStates: List<DownloadState>)
}
