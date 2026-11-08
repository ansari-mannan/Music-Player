package com.andres.musicplayer.ui.artists

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.andres.musicplayer.data.SongRepository
import com.andres.musicplayer.ui.art.SongArtwork
import com.andres.musicplayer.ui.common.EmptyState
import com.andres.musicplayer.ui.playlists.AddToPlaylistDialog
import kotlinx.coroutines.launch

private const val UNKNOWN_ARTIST = "Unknown artist"

private data class ArtistEntry(val name: String, val count: Int, val sampleUri: String?)

/** Library grouped by artist. Tap an artist to see their tracks. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArtistsScreen(
    repo: SongRepository,
    onArtistClick: (name: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // null until the first load, so the empty state never flashes up while loading.
    val loadedSongs by repo.allSongs.collectAsState(initial = null)
    val songs = loadedSongs.orEmpty()
    val playlists by repo.playlistSummaries.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var addTarget by remember { mutableStateOf<String?>(null) }
    val artists = remember(songs) {
        songs.groupBy { it.artist?.takeIf { a -> a.isNotBlank() } ?: UNKNOWN_ARTIST }
            .map { (name, list) -> ArtistEntry(name, list.size, list.firstOrNull()?.uri) }
            // Real artists sorted alphabetically; the Unknown bucket always last.
            .sortedWith(compareBy({ it.name == UNKNOWN_ARTIST }, { it.name.lowercase() }))
    }

    fun songIdsFor(artist: String): List<Long> =
        songs.filter { (it.artist?.takeIf { a -> a.isNotBlank() } ?: UNKNOWN_ARTIST) == artist }
            .map { it.id }

    if (loadedSongs == null) {
        com.andres.musicplayer.ui.common.LoadingState(modifier = modifier)
        return
    }
    if (artists.isEmpty()) {
        EmptyState(
            title = "No artists yet",
            body = "Scan your folder to import songs.",
            modifier = modifier,
        )
        return
    }

    LazyColumn(modifier = modifier.fillMaxWidth()) {
        items(items = artists, key = { it.name }) { entry ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { onArtistClick(entry.name) },
                        onLongClick = { addTarget = entry.name },
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SongArtwork(uri = entry.sampleUri, size = 48.dp)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        entry.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontStyle = if (entry.name == UNKNOWN_ARTIST) FontStyle.Italic else null,
                    )
                    Text(
                        text = if (entry.count == 1) "1 track" else "${entry.count} tracks",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider(
                modifier = Modifier.padding(start = 80.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            )
        }
    }

    addTarget?.let { artist ->
        val ids = songIdsFor(artist)
        AddToPlaylistDialog(
            playlists = playlists,
            onPick = { playlistId ->
                scope.launch { repo.addSongsToPlaylist(playlistId, ids) }
                addTarget = null
            },
            onCreateNew = { name ->
                scope.launch {
                    val id = repo.createPlaylist(name)
                    repo.addSongsToPlaylist(id, ids)
                }
                addTarget = null
            },
            onDismiss = { addTarget = null },
        )
    }
}
