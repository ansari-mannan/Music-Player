package com.andres.musicplayer.ui.playlists

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.andres.musicplayer.data.SongRepository
import com.andres.musicplayer.ui.common.CollectionHeader
import com.andres.musicplayer.ui.common.EmptyState
import com.andres.musicplayer.ui.common.LoadingState
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
        CollectionHeader(
            title = playlist?.name ?: "Playlist",
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
                    SongListCapability.EDIT_INFO,
                    SongListCapability.DELETE_FROM_LIBRARY,
                    SongListCapability.REMOVE_FROM_PLAYLIST,
                    SongListCapability.QUEUE_ACTIONS,
                ),
                repo = repo,
                onRemoveFromPlaylist = { songId ->
                    scope.launch { repo.removeFromPlaylist(playlistId, songId) }
                },
                onMove = { from, to ->
                    val ids = list.map { it.id }.toMutableList()
                    if (from in ids.indices && to in ids.indices) {
                        ids.add(to, ids.removeAt(from))
                        scope.launch { repo.reorderPlaylist(playlistId, ids) }
                    }
                },
            )
        }
    }
}
