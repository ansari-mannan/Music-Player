package com.andres.musicplayer.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A YouTube video the user cancelled or removed from the download queue. Playlist sync
 * skips these, so a cancelled download stays cancelled instead of coming back on the
 * next pass. Adding the same video again by link clears it.
 */
@Entity(tableName = "ignored_videos")
data class IgnoredVideo(
    @PrimaryKey val videoId: String,
    val ignoredAt: Long = System.currentTimeMillis(),
)
