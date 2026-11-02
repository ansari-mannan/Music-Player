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
import com.andres.musicplayer.ui.common.LoadingState
import com.andres.musicplayer.ui.player.PlayerState

/** All tracks in a single album. */
@Composable
fun SongsByAlbumScreen(
    repo: SongRepository,
    player: PlayerState,
    album: String,
    modifier: Modifier = Modifier,
) {
    val allSongs by repo.allSongs.collectAsState(initial = null)
    val songs = remember(allSongs, album) {
        allSongs?.filter { (it.album?.takeIf { a -> a.isNotBlank() } ?: "Unknown album") == album }
    }

    Column(modifier = modifier) {
        CollectionHeader(title = album, songs = songs.orEmpty(), player = player)
        when {
            songs == null -> LoadingState()
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
