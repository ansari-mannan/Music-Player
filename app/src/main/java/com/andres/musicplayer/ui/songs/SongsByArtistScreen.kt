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
    val allSongs by repo.allSongsWithTags.collectAsState(initial = null)
    val tags by repo.allTags.collectAsState(initial = emptyList())
    val songs = remember(allSongs, artist) {
        allSongs?.filter { (it.song.artist?.takeIf { a -> a.isNotBlank() } ?: UNKNOWN_ARTIST) == artist }
    }

    Column(modifier = modifier) {
        Text(
            text = artist,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        )
        when {
            songs == null -> Unit
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
