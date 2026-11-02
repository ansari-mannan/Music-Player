package com.andres.musicplayer.ui.songs

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.andres.musicplayer.data.SongRepository
import com.andres.musicplayer.ui.common.CollectionHeader
import com.andres.musicplayer.ui.common.EmptyState
import com.andres.musicplayer.ui.player.PlayerState

private const val UNKNOWN_ARTIST = "Unknown artist"

/** All tracks by a single artist. */
@Composable
fun SongsByArtistScreen(
    repo: SongRepository,
    player: PlayerState,
    artist: String,
    modifier: Modifier = Modifier,
) {
    val allSongs by repo.allSongs.collectAsState(initial = null)
    val songs = remember(allSongs, artist) {
        allSongs?.filter { (it.artist?.takeIf { a -> a.isNotBlank() } ?: UNKNOWN_ARTIST) == artist }
    }

    Column(modifier = modifier) {
        CollectionHeader(title = artist, songs = songs.orEmpty(), player = player)
        when {
            songs == null -> Unit
            songs.isEmpty() -> EmptyState(title = "No tracks", body = "")
            else -> UnifiedSongList(
                songs = songs,
                player = player,
                capabilities = setOf(
                    SongListCapability.PLAY,
                    SongListCapability.MULTI_SELECT,
                    SongListCapability.EDIT_INFO,
                    SongListCapability.DELETE_FROM_LIBRARY,
                    SongListCapability.QUEUE_ACTIONS,
                ),
                repo = repo,
            )
        }
    }
}
