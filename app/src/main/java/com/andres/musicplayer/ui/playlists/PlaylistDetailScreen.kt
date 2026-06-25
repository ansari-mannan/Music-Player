package com.andres.musicplayer.ui.playlists

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import com.andres.musicplayer.data.SongRepository
import com.andres.musicplayer.data.local.entity.SongWithTags
import com.andres.musicplayer.ui.art.AudioArt
import com.andres.musicplayer.ui.common.EmptyState
import com.andres.musicplayer.ui.common.LoadingState
import com.andres.musicplayer.ui.player.PlayIcon
import com.andres.musicplayer.ui.player.PlayerState
import com.andres.musicplayer.ui.songs.SongListCapability
import com.andres.musicplayer.ui.songs.UnifiedSongList
import kotlinx.coroutines.launch

/** A single playlist: a VLC-style header over a reorderable, selectable track list. */
@Composable
fun PlaylistDetailScreen(
    repo: SongRepository,
    player: PlayerState,
    playlistId: Long,
    modifier: Modifier = Modifier,
) {
    val playlist by repo.playlist(playlistId).collectAsState(initial = null)
    val songs by repo.playlistSongs(playlistId).collectAsState(initial = null)
    val scope = rememberCoroutineScope()

    Column(modifier = modifier.fillMaxSize()) {
        PlaylistHeader(
            name = playlist?.name ?: "Playlist",
            songs = songs.orEmpty(),
            player = player,
        )

        val list = songs
        when {
            list == null -> LoadingState()
            list.isEmpty() -> EmptyState(
                title = "Empty playlist",
                body = "Add songs from the library: select songs, then \"Add to playlist\".",
            )

            else -> UnifiedSongList(
                songs = list,
                player = player,
                capabilities = setOf(
                    SongListCapability.PLAY,
                    SongListCapability.MULTI_SELECT,
                    SongListCapability.REMOVE_FROM_PLAYLIST,
                    SongListCapability.QUEUE_ACTIONS,
                ),
                onRemoveFromPlaylist = { songId ->
                    scope.launch { repo.removeFromPlaylist(playlistId, songId) }
                },
                onMove = { from, to ->
                    val ids = list.map { it.song.id }.toMutableList()
                    if (from in ids.indices && to in ids.indices) {
                        ids.add(to, ids.removeAt(from))
                        scope.launch { repo.reorderPlaylist(playlistId, ids) }
                    }
                },
            )
        }
    }
}

/** "Facebook-profile" header: collage + blurred backdrop + play / shuffle / loop. */
@Composable
private fun PlaylistHeader(
    name: String,
    songs: List<SongWithTags>,
    player: PlayerState,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp),
    ) {
        val firstUri = songs.firstOrNull()?.song?.uri
        if (firstUri != null) {
            AsyncImage(
                model = AudioArt(Uri.parse(firstUri)),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = 0.5f,
                modifier = Modifier
                    .matchParentSize()
                    .blur(36.dp),
            )
        }
        // Fade the backdrop into the surface so the list below blends in.
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, MaterialTheme.colorScheme.surface),
                    ),
                ),
        )

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlaylistCollage(uris = songs.take(4).map { it.song.uri }, size = 120.dp)
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (songs.size == 1) "1 song" else "${songs.size} songs",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledIconButton(
                        onClick = { if (songs.isNotEmpty()) player.play(songs.first(), songs) },
                    ) {
                        PlayIcon(Modifier.size(22.dp))
                    }
                    IconButton(
                        onClick = {
                            if (songs.isNotEmpty()) {
                                player.play(songs.first(), songs)
                                if (!player.shuffle) player.toggleShuffle()
                            }
                        },
                    ) {
                        Icon(
                            Icons.Default.Shuffle,
                            contentDescription = "Shuffle play",
                            tint = if (player.shuffle) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                    IconButton(onClick = { player.cycleRepeat() }) {
                        Icon(
                            imageVector = if (player.repeatMode == Player.REPEAT_MODE_ONE) {
                                Icons.Default.RepeatOne
                            } else {
                                Icons.Default.Repeat
                            },
                            contentDescription = "Loop",
                            tint = if (player.repeatMode != Player.REPEAT_MODE_OFF) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }
        }
    }
}
