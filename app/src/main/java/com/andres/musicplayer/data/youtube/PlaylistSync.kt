package com.andres.musicplayer.data.youtube

import android.content.Context
import android.util.Log
import com.andres.musicplayer.data.local.AppDatabase
import com.andres.musicplayer.data.local.dao.DownloadDao
import com.andres.musicplayer.data.local.dao.DownloadedVideoDao
import com.andres.musicplayer.data.local.dao.WatchedPlaylistDao
import com.andres.musicplayer.data.local.entity.DownloadState
import com.andres.musicplayer.data.prefs.SyncPreferences
import com.andres.musicplayer.download.DownloadEngine
import com.andres.musicplayer.download.PendingDownload
import kotlinx.coroutines.sync.Mutex

/** What one sync pass did, for the Settings line and for logging. */
data class SyncResult(
    val playlistsChecked: Int,
    val queued: Int,
    val skippedPlaylists: Int,
    val error: String? = null,
) {
    /** One short sentence, shown under the sync setting. */
    fun describe(): String = when {
        error != null -> error
        playlistsChecked == 0 -> "No playlists are being synced."
        queued == 0 -> "Checked $playlistsChecked — nothing new."
        queued == 1 -> "Checked $playlistsChecked — 1 new song queued."
        else -> "Checked $playlistsChecked — $queued new songs queued."
    }
}

/**
 * Diffs every watched playlist against what's already downloaded or queued, and hands
 * anything new to [DownloadEngine].
 *
 * Shared by both triggers (app open, and the periodic worker) so there is exactly one
 * definition of "what syncing means". Concurrent calls are serialised rather than
 * queued: if a sync is already running, a second request returns immediately instead of
 * doubling the API traffic and racing on the same rows.
 */
class PlaylistSync(
    private val auth: YouTubeAuth,
    private val api: YouTubeApi,
    private val watchedDao: WatchedPlaylistDao,
    private val downloadDao: DownloadDao,
    private val downloadedVideoDao: DownloadedVideoDao,
    private val engine: DownloadEngine,
    private val prefs: SyncPreferences,
) {
    private val mutex = Mutex()

    /**
     * Runs one pass. Returns null if a sync was already in flight, or if [minimumGapMs]
     * hasn't elapsed since the last one — the app-open trigger uses that to avoid
     * hammering the API when the user flicks between apps.
     */
    suspend fun syncIfDue(minimumGapMs: Long = 0L): SyncResult? {
        val sinceLast = System.currentTimeMillis() - prefs.lastSyncAt
        if (minimumGapMs > 0 && prefs.lastSyncAt > 0 && sinceLast < minimumGapMs) return null
        return sync()
    }

    /**
     * Runs one pass unconditionally (beyond the "not already running" guard).
     *
     * `tryLock` rather than checking `isLocked` first: two triggers firing together —
     * the app being opened just as the periodic worker wakes — would both see an
     * unlocked mutex and then both run.
     */
    suspend fun sync(): SyncResult? {
        if (!mutex.tryLock()) return null
        return try {
            runSync().also { result ->
                prefs.lastSyncAt = System.currentTimeMillis()
                prefs.lastSyncSummary = result.describe()
            }
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun runSync(): SyncResult {
        if (!auth.isConnected) {
            return SyncResult(0, 0, 0, error = "No YouTube account connected.")
        }

        val watched = watchedDao.getWatched()
        // Pending work for playlists that are no longer watched is stale. History is
        // deliberately left alone — see DownloadDao.deletePendingForUnwatched.
        pruneUnwatched(watched.map { it.playlistId })
        if (watched.isEmpty()) return SyncResult(0, 0, 0)

        // One snapshot of "already have it" for the whole pass, then kept up to date
        // locally so two playlists containing the same video only queue it once.
        val known = HashSet<String>()
        known += downloadedVideoDao.allVideoIds()
        known += downloadDao.allVideoIds()

        var queued = 0
        var checked = 0
        var skipped = 0
        var firstError: String? = null

        for (playlist in watched) {
            val videos = try {
                withToken { api.listPlaylistItems(it, playlist.playlistId) }
            } catch (e: Exception) {
                // One unreachable playlist shouldn't abandon the rest of the pass.
                Log.w(TAG, "Sync failed for ${playlist.playlistId}", e)
                skipped++
                if (firstError == null) firstError = describe(e)
                continue
            }

            // Batched so a playlist with fifty new songs starts the download service
            // once rather than fifty times.
            val fresh = videos
                .filter { known.add(it.videoId) }
                .map {
                    PendingDownload(
                        videoId = it.videoId,
                        title = it.title,
                        thumbnailUrl = it.thumbnailUrl,
                        sourcePlaylistId = playlist.playlistId,
                    )
                }
            queued += engine.enqueueAll(fresh)
            watchedDao.markChecked(playlist.playlistId, System.currentTimeMillis())
            checked++
        }

        // Only surface an error if nothing at all worked; a partial pass is still useful.
        val error = firstError?.takeIf { checked == 0 }
        return SyncResult(checked, queued, skipped, error)
    }

    private suspend fun pruneUnwatched(watchedIds: List<String>) {
        val pending = listOf(DownloadState.QUEUED, DownloadState.PAUSED)
        if (watchedIds.isEmpty()) {
            downloadDao.deleteAllPendingFromPlaylists(pending)
        } else {
            downloadDao.deletePendingForUnwatched(watchedIds, pending)
        }
    }

    /** Same one-shot retry as the playlist refresh: a token can expire mid-pass. */
    private suspend fun <T> withToken(block: suspend (String) -> T): T {
        val token = auth.requireAccessToken()
        return try {
            block(token)
        } catch (unauthorized: YouTubeUnauthorizedException) {
            auth.invalidateToken()
            block(auth.requireAccessToken())
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is NotConnectedException -> e.message ?: "YouTube account isn't connected."
        is YouTubeApiException -> e.message ?: "YouTube rejected the request."
        else -> e.message ?: "Couldn't reach YouTube."
    }

    companion object {
        private const val TAG = "PlaylistSync"

        /** App-open syncs no more often than this, to survive rapid app switching. */
        const val APP_OPEN_GAP_MS = 60_000L

        fun from(context: Context, engine: DownloadEngine, auth: YouTubeAuth): PlaylistSync {
            val db = AppDatabase.getInstance(context)
            return PlaylistSync(
                auth = auth,
                api = YouTubeApi(),
                watchedDao = db.watchedPlaylistDao(),
                downloadDao = db.downloadDao(),
                downloadedVideoDao = db.downloadedVideoDao(),
                engine = engine,
                prefs = SyncPreferences(context),
            )
        }
    }
}
