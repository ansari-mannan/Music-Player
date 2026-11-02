package com.andres.musicplayer.ui.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Download
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
    const val DOWNLOADS = "downloads"
    const val SETTINGS = "settings"

    // Opened from Settings.
    const val EQUALIZER = "equalizer"
    const val SYNCED_PLAYLISTS = "synced_playlists"

    // Pushed detail / full screens.
    const val NOW_PLAYING = "now_playing"
    const val ARTIST_SONGS = "artist/{name}"
    const val ALBUM_SONGS = "album/{name}"
    const val PLAYLIST_DETAIL = "playlist/{id}"

    fun artistSongs(encodedName: String) = "artist/$encodedName"
    fun albumSongs(encodedName: String) = "album/$encodedName"
    fun playlistDetail(id: Long) = "playlist/$id"
}

/** A bottom-navigation destination. */
data class BottomDestination(val route: String, val label: String, val icon: ImageVector)

/** Settings stays last, by convention; Downloads sits with the content destinations. */
val bottomDestinations = listOf(
    BottomDestination(Routes.AUDIO, "Audio", Icons.Default.LibraryMusic),
    BottomDestination(Routes.BROWSE, "Browse", Icons.Default.FolderOpen),
    BottomDestination(Routes.PLAYLISTS, "Playlists", Icons.AutoMirrored.Filled.QueueMusic),
    BottomDestination(Routes.DOWNLOADS, "Downloads", Icons.Default.Download),
    BottomDestination(Routes.SETTINGS, "Settings", Icons.Default.Settings),
)

/** Top tabs inside the Audio section. */
enum class AudioTab(val label: String) {
    ARTISTS("Artists"),
    ALBUMS("Albums"),
    TRACKS("Tracks"),
    PLAYLISTS("Playlists"),
}
