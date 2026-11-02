package com.andres.musicplayer.data.local.entity

import androidx.room.Embedded

/**
 * A download row plus the display name of the playlist it came from.
 *
 * [playlistTitle] is null in two legitimate cases: the download was started by hand
 * (no source playlist at all), or the playlist has since been deleted on YouTube. The
 * join is a LEFT JOIN precisely so the second case keeps the history row rather than
 * dropping it — losing finished-download history when a playlist goes away is the exact
 * behaviour the foreign key was removed to avoid.
 */
data class DownloadEntryWithSource(
    @Embedded val entry: DownloadEntry,
    val playlistTitle: String?,
)
