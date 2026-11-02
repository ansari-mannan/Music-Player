package com.andres.musicplayer.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The permanent "this one is already on disk" ledger. Every sync pass diffs the videos
 * a watched playlist returns against this table, so a video is fetched exactly once.
 *
 * Deliberately *not* foreign-keyed to [WatchedPlaylist]: this has to outlive the
 * playlist it came from. If it cascaded away when a playlist was deleted on YouTube,
 * every video in it would look new again and be re-downloaded.
 *
 * [filePath] records where the file landed in the watched folder. It is a record, not
 * ownership — the file's fate belongs entirely to the library and the user.
 */
@Entity(
    tableName = "downloaded_videos",
    indices = [Index("sourcePlaylistId")],
)
data class DownloadedVideo(
    @PrimaryKey val videoId: String,
    /** Null when the download was started by hand rather than by a playlist sync. */
    val sourcePlaylistId: String? = null,
    val filePath: String,
    val downloadedAt: Long = System.currentTimeMillis(),
)
