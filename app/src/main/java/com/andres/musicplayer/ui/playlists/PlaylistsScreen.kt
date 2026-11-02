package com.andres.musicplayer.ui.playlists

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andres.musicplayer.data.SongRepository
import com.andres.musicplayer.data.local.entity.PlaylistWithSongs
import com.andres.musicplayer.ui.common.EmptyState
import com.andres.musicplayer.ui.player.PlusIcon
import kotlinx.coroutines.launch

/** Grid of playlist tiles (4-up cover, name, count). Tap to open, long-press to delete. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlaylistsScreen(
    repo: SongRepository,
    onOpenPlaylist: (id: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val playlists by repo.allPlaylistsWithSongs.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var showCreate by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<PlaylistWithSongs?>(null) }

    // Square cover that fills a 2-column cell (padding 16 outer, 12 gap).
    val coverSize = ((LocalConfiguration.current.screenWidthDp - 16 * 2 - 12) / 2).dp

    Box(modifier = modifier.fillMaxSize()) {
        if (playlists.isEmpty()) {
            EmptyState(
                title = "No playlists yet",
                body = "Tap + to create one.",
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items = playlists, key = { it.playlist.id }) { item ->
                    Column(
                        modifier = Modifier.combinedClickable(
                            onClick = { onOpenPlaylist(item.playlist.id) },
                            onLongClick = { deleteTarget = item },
                        ),
                    ) {
                        PlaylistCollage(uris = item.songs.map { it.uri }, size = coverSize)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            item.playlist.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = if (item.songs.size == 1) "1 song" else "${item.songs.size} songs",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { showCreate = true },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        ) {
            PlusIcon(Modifier.size(24.dp))
        }
    }

    if (showCreate) {
        PlaylistNameDialog(
            title = "New playlist",
            initial = "",
            onDismiss = { showCreate = false },
            onConfirm = { name ->
                scope.launch { repo.createPlaylist(name) }
                showCreate = false
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete \"${target.playlist.name}\"?") },
            text = { Text("The playlist is removed. Your songs stay in the library.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { repo.deletePlaylist(target.playlist.id) }
                    deleteTarget = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
fun PlaylistNameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onConfirm(name.trim()) },
                enabled = name.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
