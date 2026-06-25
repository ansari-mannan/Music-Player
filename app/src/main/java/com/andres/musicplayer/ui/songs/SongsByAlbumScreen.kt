package com.andres.musicplayer.ui.songs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.andres.musicplayer.data.SongRepository
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
    val allSongs by repo.allSongsWithTags.collectAsState(initial = null)
    val tags by repo.allTags.collectAsState(initial = emptyList())
    val songs = remember(allSongs, album) {
        allSongs?.filter { (it.song.album?.takeIf { a -> a.isNotBlank() } ?: "Unknown album") == album }
    }

    Column(modifier = modifier) {
        Text(
            text = album,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        )
        when {
            songs == null -> LoadingState()
            songs.isEmpty() -> EmptyState(title = "No tracks", body = "")
            else -> UnifiedSongList(
                songs = songs,
                player = player,
                capabilities = setOf(
                    SongListCapability.PLAY,
                    SongListCapability.MULTI_SELECT,
                    SongListCapability.TAG,
                    SongListCapability.QUEUE_ACTIONS,
                ),
                repo = repo,
                allTags = tags,
            )
        }
    }
}
