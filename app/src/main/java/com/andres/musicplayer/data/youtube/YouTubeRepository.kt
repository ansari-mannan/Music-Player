package com.andres.musicplayer.data.youtube

import android.content.Context
import com.andres.musicplayer.data.local.AppDatabase
import com.andres.musicplayer.data.local.dao.WatchedPlaylistDao
import com.andres.musicplayer.data.local.entity.WatchedPlaylist
import kotlinx.coroutines.flow.Flow

/**
 * Single entry point for everything YouTube-account related: the connection itself, the
 * playlists discovered on it, and which of them the user opted to sync.
 *
 * Kept separate from `SongRepository` on purpose — that one owns the on-device library,
 * and the library must not grow a dependency on the network. The two meet only at the
 * end of the pipeline, where a downloaded file lands in the watched folder and the
 * existing scan picks it up like any other file.
 */
class YouTubeRepository(
    val auth: YouTubeAuth,
    private val api: YouTubeApi,
    private val dao: WatchedPlaylistDao,
) {
    /** Every playlist on the account, watched or not — what the picker renders. */
    val playlists: Flow<List<WatchedPlaylist>> = dao.getAll()

    val isConnected: Boolean get() = auth.isConnected
    val accountLabel: String? get() = auth.accountLabel

    /**
     * Re-reads the account's playlists from the API and reconciles them into Room.
     * Existing "sync this playlist" choices are preserved; playlists deleted on YouTube
     * disappear from the picker.
     */
    suspend fun refreshPlaylists() {
        val remote = withToken { token ->
            ensureAccountLabel(token)
            api.listMyPlaylists(token)
        }
        dao.replaceRemote(
            remote.map {
                WatchedPlaylist(
                    playlistId = it.id,
                    title = it.title,
                    thumbnailUrl = it.thumbnailUrl,
                    itemCount = it.itemCount,
                )
            },
        )
    }

    suspend fun setWatched(playlistId: String, watched: Boolean) =
        dao.setWatched(playlistId, watched)

    /** The playlists the sync pass should check. Used from Section 3.3 onwards. */
    suspend fun watchedPlaylists(): List<WatchedPlaylist> = dao.getWatched()

    /** Clears the local connection and the mirrored playlists along with it. */
    suspend fun disconnect() {
        auth.disconnect()
        dao.deleteAll()
    }

    /**
     * Runs [block] with a valid access token, retrying once against a freshly-minted
     * token if the first attempt is rejected — a token can be revoked or expire between
     * the cache check and the request landing.
     */
    private suspend fun <T> withToken(block: suspend (String) -> T): T {
        val token = auth.requireAccessToken()
        return try {
            block(token)
        } catch (unauthorized: YouTubeUnauthorizedException) {
            auth.invalidateToken()
            block(auth.requireAccessToken())
        }
    }

    /**
     * Labels the connection with the account's channel name when the authorization
     * itself didn't carry an email. Best-effort: a failure here must not fail a refresh.
     */
    private suspend fun ensureAccountLabel(token: String) {
        if (auth.accountLabel != null) return
        runCatching { api.myChannelTitle(token) }
            .getOrNull()
            ?.let { auth.saveAccountLabel(it) }
    }

    companion object {
        fun from(context: Context): YouTubeRepository = YouTubeRepository(
            auth = YouTubeAuth(context),
            api = YouTubeApi(),
            dao = AppDatabase.getInstance(context).watchedPlaylistDao(),
        )
    }
}
