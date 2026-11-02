package com.andres.musicplayer.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One playlist on the connected YouTube account, mirrored locally so the picker can
 * be shown offline and so [isWatched] survives restarts.
 *
 * Every playlist the account owns gets a row — [isWatched] is what marks the ones the
 * user actually wants synced, and it defaults to false (opt-in). Title, thumbnail and
 * item count are refreshed from the API on each discovery pass; [isWatched] never is.
 */
@Entity(tableName = "watched_playlists")
data class WatchedPlaylist(
    @PrimaryKey val playlistId: String,
    val title: String,
    /** Empty when the playlist has no thumbnail (a brand-new, still-empty playlist). */
    val thumbnailUrl: String,
    val itemCount: Int,
    val isWatched: Boolean = false,
    /** Epoch millis of the last sync pass over this playlist; null until first synced. */
    val lastCheckedAt: Long? = null,
)
