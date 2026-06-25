package com.andres.musicplayer.ui.songs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.andres.musicplayer.data.SongRepository
import com.andres.musicplayer.ui.common.EmptyState
import com.andres.musicplayer.ui.common.LoadingState
import com.andres.musicplayer.ui.player.PlayerState

/** Songs that carry a single mood tag. */
@Composable
fun SongsByMoodScreen(
    repo: SongRepository,
    player: PlayerState,
    tagId: Long,
    modifier: Modifier = Modifier,
) {
    val songs by repo.songsByTag(tagId).collectAsState(initial = null)
    val tags by repo.allTags.collectAsState(initial = emptyList())
    val moodName = tags.firstOrNull { it.id == tagId }?.name

    Column(modifier = modifier) {
        if (moodName != null) {
            Text(
                text = moodName,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
            )
        }
        when {
            songs == null -> LoadingState()
            songs!!.isEmpty() -> EmptyState(
                title = "No songs with this mood",
                body = "Long-press a song on the Songs screen to tag it.",
            )

            else -> UnifiedSongList(
                songs = songs!!,
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
