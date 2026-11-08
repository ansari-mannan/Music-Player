package com.andres.musicplayer.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A file the user deleted from the library. The file itself is never touched, so without
 * this the next folder scan would find it again and quietly bring it back.
 *
 * [documentId] is the SAF document id (e.g. `primary:Music/song.m4a`), which stays the
 * same when the folder is re-picked through a different tree, so the exclusion survives
 * that too.
 */
@Entity(tableName = "excluded_songs")
data class ExcludedSong(
    @PrimaryKey val uri: String,
    val documentId: String?,
    val excludedAt: Long = System.currentTimeMillis(),
)
