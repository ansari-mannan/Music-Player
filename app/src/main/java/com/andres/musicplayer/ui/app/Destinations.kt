package com.andres.musicplayer.ui.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/** Navigation routes. */
object Routes {
    // Bottom-nav destinations.
    const val AUDIO = "audio"
    const val BROWSE = "browse"
    const val PLAYLISTS = "playlists"
    const val SETTINGS = "settings"

    // The mood-blend mix generator (opened by the center button).
    const val MIX = "mix"

    // Equalizer (opened from Settings).
    const val EQUALIZER = "equalizer"

    // Pushed detail / full screens.
    const val NOW_PLAYING = "now_playing"
    const val MOOD_SONGS = "mood/{tagId}"
    const val ARTIST_SONGS = "artist/{name}"
    const val ALBUM_SONGS = "album/{name}"
    const val PLAYLIST_DETAIL = "playlist/{id}"

    fun moodSongs(tagId: Long) = "mood/$tagId"
    fun artistSongs(encodedName: String) = "artist/$encodedName"
    fun albumSongs(encodedName: String) = "album/$encodedName"
    fun playlistDetail(id: Long) = "playlist/$id"
}

/** A bottom-navigation destination. */
data class BottomDestination(val route: String, val label: String, val icon: ImageVector)

val bottomDestinations = listOf(
    BottomDestination(Routes.AUDIO, "Audio", Icons.Default.LibraryMusic),
    BottomDestination(Routes.BROWSE, "Browse", Icons.Default.FolderOpen),
    BottomDestination(Routes.PLAYLISTS, "Playlists", Icons.AutoMirrored.Filled.QueueMusic),
    BottomDestination(Routes.SETTINGS, "Settings", Icons.Default.Settings),
)

/** Top tabs inside the Audio section. */
enum class AudioTab(val label: String) {
    ARTISTS("Artists"),
    ALBUMS("Albums"),
    TRACKS("Tracks"),
    MOODS("Moods"),
    MIXES("Mixes"),
    PLAYLISTS("Playlists"),
}
