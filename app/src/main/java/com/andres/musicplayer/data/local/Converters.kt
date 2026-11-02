package com.andres.musicplayer.data.local

import androidx.room.TypeConverter
import com.andres.musicplayer.data.local.entity.DownloadState

/**
 * Stores [DownloadState] as its name rather than an ordinal, so reordering the enum
 * can't silently reinterpret existing rows.
 *
 * Written out explicitly instead of leaning on Room's built-in enum handling because
 * that throws on an unrecognised value; here an unknown name degrades to [FAILED],
 * which the user can see and retry rather than a crash on startup.
 */
object Converters {

    @TypeConverter
    fun toDownloadState(value: String?): DownloadState = when (value) {
        null -> DownloadState.FAILED
        else -> runCatching { DownloadState.valueOf(value) }
            .getOrDefault(DownloadState.FAILED)
    }

    @TypeConverter
    fun fromDownloadState(state: DownloadState): String = state.name
}
