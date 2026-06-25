package com.andres.musicplayer.data.local.entity

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation

/** A song together with all of its tags, resolved through the join table. */
data class SongWithTags(
    @Embedded val song: Song,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = SongTagCrossRef::class,
            parentColumn = "songId",
            entityColumn = "tagId",
        ),
    )
    val tags: List<Tag>,
)
