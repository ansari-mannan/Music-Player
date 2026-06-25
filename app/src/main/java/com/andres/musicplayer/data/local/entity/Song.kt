package com.andres.musicplayer.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One audio file in the local library.
 *
 * [uri] is unique so the same file is never added twice (used by the Phase 2
 * folder scan). For now the debug screen just puts a placeholder value there.
 */
@Entity(
    tableName = "songs",
    indices = [Index(value = ["uri"], unique = true)],
)
data class Song(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    /** Track length in milliseconds. */
    val durationMs: Long = 0,
    /** When the song was added to the library, epoch millis. */
    val dateAdded: Long = System.currentTimeMillis(),
)
