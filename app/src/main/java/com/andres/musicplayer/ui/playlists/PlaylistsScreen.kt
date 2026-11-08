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
import com.andres.musicplayer.data.local.dao.PlaylistSummary
import com.andres.musicplayer.ui.common.LoadingState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.clickable
import com.andres.musicplayer.ui.common.EmptyState
import com.andres.musicplayer.ui.player.PlusIcon
import kotlinx.coroutines.launch

/** Grid of playlist tiles (4-up cover, name, count). Tap to open, long-press to rename or delete. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlaylistsScreen(
    repo: SongRepository,
    onOpenPlaylist: (id: Long) -> Unit,
    modifier: Modifier = Modifier,
    /** Set on a fresh install (no music folder yet): the screen offers to pick one instead. */
    onPickFolder: (() -> Unit)? = null,
) {
    // null until the first load, so an empty state never flashes up while loading.
    val loaded by repo.playlistSummaries.collectAsState(initial = null)
    val playlists = loaded.orEmpty()
    val scope = rememberCoroutineScope()
    var showCreate by remember { mutableStateOf(false) }
    var menuTarget by remember { mutableStateOf<PlaylistSummary?>(null) }
    var renameTarget by remember { mutableStateOf<PlaylistSummary?>(null) }
    var deleteTarget by remember { mutableStateOf<PlaylistSummary?>(null) }

    // Square cover that fills a 2-column cell (padding 16 outer, 12 gap).
    val coverSize = ((LocalConfiguration.current.screenWidthDp - 16 * 2 - 12) / 2).dp

    Box(modifier = modifier.fillMaxSize()) {
        if (onPickFolder != null) {
            // First launch: Playlists is the start screen, so the setup step goes here.
            EmptyState(
                title = "Welcome to ${com.andres.musicplayer.ui.app.LocalAppTitle.current}",
                body = "Your library lives in a folder on your phone. Tap below — the picker " +
                    "opens in your Music folder, so just tap \"Use this folder\" and then \"Allow\".",
                buttonText = "Use my Music folder",
                onButtonClick = onPickFolder,
            )
            return@Box
        } else if (loaded == null) {
            LoadingState()
        } else if (playlists.isEmpty()) {
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
                items(items = playlists, key = { it.id }) { item ->
                    Column(
                        modifier = Modifier.combinedClickable(
                            onClick = { onOpenPlaylist(item.id) },
                            onLongClick = { menuTarget = item },
                        ),
                    ) {
                        PlaylistCollage(uris = item.covers, size = coverSize)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            item.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = if (item.songCount == 1) "1 song" else "${item.songCount} songs",
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

    menuTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { menuTarget = null },
            title = { Text(target.name) },
            text = {
                Column {
                    Text(
                        "Rename",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { renameTarget = target; menuTarget = null }
                            .padding(vertical = 12.dp),
                    )
                    Text(
                        "Delete",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { deleteTarget = target; menuTarget = null }
                            .padding(vertical = 12.dp),
                    )
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { menuTarget = null }) { Text("Cancel") } },
        )
    }

    renameTarget?.let { target ->
        PlaylistNameDialog(
            title = "Rename playlist",
            initial = target.name,
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                scope.launch { repo.renamePlaylist(target.id, name) }
                renameTarget = null
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete \"${target.name}\"?") },
            text = { Text("The playlist is removed. Your songs stay in the library.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { repo.deletePlaylist(target.id) }
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
