package com.andres.musicplayer.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Where a download currently stands. Stored by name, so the DB stays readable. */
enum class DownloadState {
    QUEUED,
    DOWNLOADING,
    PAUSED,
    FAILED,
    COMPLETED,
}

/**
 * One unit of download work, and the row the Download Manager UI renders.
 *
 * [bytesDownloaded] is persisted as the download runs so a pause (or a process death)
 * can be resumed from a byte offset via an HTTP Range request rather than restarting.
 *
 * [sourcePlaylistId] is an ordinary indexed column rather than a foreign key, and is
 * null for a download started by hand. It started out as a cascading FK to
 * [WatchedPlaylist], which turned out to be wrong twice over: a manual download has no
 * playlist to point at, and cascading meant unwatching a playlist (or disconnecting the
 * account) silently erased the completed-download history along with it. What should
 * actually happen when a playlist goes away — drop its *pending* work, keep its history
 * — is a policy call, so it lives in the sync logic where it can be read, not in a
 * delete rule. [DownloadedVideo] stays unkeyed for the same reason.
 */
@Entity(
    tableName = "download_entries",
    indices = [Index("sourcePlaylistId")],
)
data class DownloadEntry(
    /**
     * Currently always the video id. Kept distinct from [videoId] so a future retry can
     * be modelled as a second entry for the same video without a primary-key clash.
     */
    @PrimaryKey val id: String,
    val videoId: String,
    val title: String,
    val thumbnailUrl: String,
    /** Null when the download was started by hand rather than by a playlist sync. */
    val sourcePlaylistId: String? = null,
    val state: DownloadState = DownloadState.QUEUED,
    val bytesDownloaded: Long = 0L,
    /** Null until the server reports a content length (some streams never do). */
    val totalBytes: Long? = null,
    /** Human-readable, shown verbatim in the Failed section. Null unless FAILED. */
    val failureReason: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    /**
     * True only once a cover has been written into the downloaded file **and read back
     * out of it again**. Not the same thing as [thumbnailUrl], which the playlist listing
     * always supplies and which the Completed list used to render regardless — showing
     * artwork for files that had none, which is precisely how a silently broken `covr`
     * write went unnoticed.
     *
     * The SQL default is declared here as well as in the migration so a fresh install and
     * an upgraded one end up with byte-identical tables — SQLite requires a default when
     * adding a NOT NULL column, and without this the two would quietly differ.
     */
    @ColumnInfo(defaultValue = "0")
    val hasEmbeddedArt: Boolean = false,
)
