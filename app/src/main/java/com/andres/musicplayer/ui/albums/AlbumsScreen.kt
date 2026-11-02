package com.andres.musicplayer.ui.albums

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andres.musicplayer.data.SongRepository
import com.andres.musicplayer.ui.art.ArtworkVersion
import com.andres.musicplayer.ui.art.SongArtwork
import com.andres.musicplayer.ui.common.EmptyState
import com.andres.musicplayer.ui.playlists.AddToPlaylistDialog
import kotlinx.coroutines.launch

private const val UNKNOWN_ALBUM = "Unknown album"

private data class AlbumEntry(
    val name: String,
    val artist: String?,
    val count: Int,
    val sampleUri: String?,
)

/** Library grouped by album; songs with no album fall into an "Unknown album" bucket. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumsScreen(
    repo: SongRepository,
    onAlbumClick: (name: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val songs by repo.allSongs.collectAsState(initial = emptyList())
    val playlists by repo.allPlaylistsWithSongs.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var addTarget by remember { mutableStateOf<String?>(null) }
    var manageAlbum by remember { mutableStateOf<String?>(null) }
    // The album whose artwork the picker (below) will apply to, captured at launch.
    var artworkAlbum by remember { mutableStateOf<String?>(null) }

    val artworkPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        val album = artworkAlbum
        if (uri != null && album != null) {
            scope.launch {
                if (repo.writeArtworkToAlbumFromUri(album, uri) > 0) ArtworkVersion.bump()
            }
        }
        artworkAlbum = null
    }

    fun songIdsFor(album: String): List<Long> =
        songs.filter { (it.album?.takeIf { a -> a.isNotBlank() } ?: UNKNOWN_ALBUM) == album }
            .map { it.id }

    val albums = remember(songs) {
        songs.groupBy { it.album?.takeIf { a -> a.isNotBlank() } ?: UNKNOWN_ALBUM }
            .map { (name, list) ->
                AlbumEntry(
                    name = name,
                    artist = list.firstOrNull { !it.artist.isNullOrBlank() }?.artist,
                    count = list.size,
                    sampleUri = list.firstOrNull()?.uri,
                )
            }
            // Real albums sorted alphabetically; the Unknown bucket always last.
            .sortedWith(compareBy({ it.name == UNKNOWN_ALBUM }, { it.name.lowercase() }))
    }

    if (albums.isEmpty()) {
        EmptyState(
            title = "No albums yet",
            body = "Long-press a song, then Edit info to add an album — it'll show up here.",
            modifier = modifier,
        )
        return
    }

    // Square cover that fills a 2-column cell (padding 16 outer, 12 gap).
    val coverSize = ((LocalConfiguration.current.screenWidthDp - 16 * 2 - 12) / 2).dp

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items = albums, key = { it.name }) { entry ->
            Column(
                modifier = Modifier.combinedClickable(
                    onClick = { onAlbumClick(entry.name) },
                    onLongClick = { manageAlbum = entry.name },
                ),
            ) {
                SongArtwork(uri = entry.sampleUri, size = coverSize)
                Spacer(Modifier.height(6.dp))
                Text(
                    entry.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontStyle = if (entry.name == UNKNOWN_ALBUM) FontStyle.Italic else null,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${entry.artist ?: "Unknown artist"} · " +
                        if (entry.count == 1) "1 track" else "${entry.count} tracks",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    manageAlbum?.let { album ->
        AlertDialog(
            onDismissRequest = { manageAlbum = null },
            title = { Text(album) },
            text = {
                Column {
                    AlbumActionRow("Add to playlist") {
                        addTarget = album
                        manageAlbum = null
                    }
                    AlbumActionRow("Change album artwork") {
                        artworkAlbum = album
                        manageAlbum = null
                        artworkPicker.launch("image/*")
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { manageAlbum = null }) { Text("Cancel") }
            },
        )
    }

    addTarget?.let { album ->
        val ids = songIdsFor(album)
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

@Composable
private fun AlbumActionRow(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    )
}
