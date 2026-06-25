package com.andres.musicplayer.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Join table linking songs and tags (a song can have many tags and vice versa).
 * Deleting a song or tag cascades to remove its links.
 */
@Entity(
    tableName = "song_tag_cross_ref",
    primaryKeys = ["songId", "tagId"],
    foreignKeys = [
        ForeignKey(
            entity = Song::class,
            parentColumns = ["id"],
            childColumns = ["songId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = Tag::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    // songId is covered by the primary key; tagId needs its own index.
    indices = [Index("tagId")],
)
data class SongTagCrossRef(
    val songId: Long,
    val tagId: Long,
)
