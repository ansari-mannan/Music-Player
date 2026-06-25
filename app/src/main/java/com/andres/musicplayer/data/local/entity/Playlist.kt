package com.andres.musicplayer.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A user-created (or mix-generated) playlist. */
@Entity(tableName = "playlists")
data class Playlist(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
)
