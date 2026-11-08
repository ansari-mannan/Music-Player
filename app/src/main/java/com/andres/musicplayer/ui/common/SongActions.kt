package com.andres.musicplayer.ui.common

import androidx.compose.runtime.staticCompositionLocalOf
import com.andres.musicplayer.data.local.entity.Song

/**
 * App-level actions any song list or player screen can offer, provided once by the app
 * shell instead of being threaded through every screen's parameters.
 */
class SongActions(
    /** Opens the trim / cut editor for a song in the library. */
    val openTrim: (Song) -> Unit = {},
)

val LocalSongActions = staticCompositionLocalOf { SongActions() }
