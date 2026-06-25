package com.andres.musicplayer.ui.moods

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.andres.musicplayer.data.local.entity.Tag
import com.andres.musicplayer.ui.common.EmptyState
import com.andres.musicplayer.ui.player.PlusIcon
import com.andres.musicplayer.ui.playlists.AddToPlaylistDialog
import com.andres.musicplayer.ui.playlists.PlaylistCollage
import kotlinx.coroutines.launch

/** Browse the library by mood tag, and create new mood tags. */
@Composable
fun MoodsScreen(
    repo: SongRepository,
    onMoodClick: (tagId: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tags by repo.allTags.collectAsState(initial = emptyList())
    val songs by repo.allSongsWithTags.collectAsState(initial = emptyList())
    val counts = remember(songs) {
        songs.flatMap { it.tags }.groupingBy { it.id }.eachCount()
    }
    // Up to four sample cover uris per mood, for the collage tile.
    val moodArt = remember(songs) {
        songs.flatMap { s -> s.tags.map { it.id to s.song.uri } }
            .groupBy({ it.first }, { it.second })
    }
    val playlists by repo.allPlaylistsWithSongs.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var showCreateDialog by remember { mutableStateOf(false) }
    var manageTag by remember { mutableStateOf<Tag?>(null) }
    var renameTag by remember { mutableStateOf<Tag?>(null) }
    var deleteTag by remember { mutableStateOf<Tag?>(null) }
    var addToPlaylistTag by remember { mutableStateOf<Tag?>(null) }

    fun songIdsForMood(tagId: Long): List<Long> =
        songs.filter { it.tags.any { t -> t.id == tagId } }.map { it.song.id }

    val coverSize = ((LocalConfiguration.current.screenWidthDp - 16 * 2 - 12) / 2).dp

    Box(modifier = modifier.fillMaxSize()) {
        if (tags.isEmpty()) {
            EmptyState(
                title = "No moods yet",
                body = "Tap + to create a mood, or long-press a track to tag it.",
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items = tags, key = { it.id }) { tag ->
                    MoodTile(
                        name = tag.name,
                        count = counts[tag.id] ?: 0,
                        coverUris = moodArt[tag.id].orEmpty(),
                        coverSize = coverSize,
                        onClick = { onMoodClick(tag.id) },
                        onLongClick = { manageTag = tag },
                    )
                }
            }
        }

        FloatingActionButton(
            onClick = { showCreateDialog = true },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        ) {
            PlusIcon(Modifier.size(24.dp))
        }
    }

    if (showCreateDialog) {
        CreateTagDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name ->
                scope.launch { repo.insertTag(Tag(name = name)) }
                showCreateDialog = false
            },
        )
    }

    manageTag?.let { tag ->
        AlertDialog(
            onDismissRequest = { manageTag = null },
            title = { Text(tag.name) },
            text = {
                Column {
                    ManageRow("Add to playlist") {
                        addToPlaylistTag = tag
                        manageTag = null
                    }
                    ManageRow("Rename") {
                        renameTag = tag
                        manageTag = null
                    }
                    ManageRow("Delete") {
                        deleteTag = tag
                        manageTag = null
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { manageTag = null }) { Text("Cancel") }
            },
        )
    }

    addToPlaylistTag?.let { tag ->
        val ids = songIdsForMood(tag.id)
        AddToPlaylistDialog(
            playlists = playlists,
            onPick = { playlistId ->
                scope.launch { repo.addSongsToPlaylist(playlistId, ids) }
                addToPlaylistTag = null
            },
            onCreateNew = { name ->
                scope.launch {
                    val id = repo.createPlaylist(name)
                    repo.addSongsToPlaylist(id, ids)
                }
                addToPlaylistTag = null
            },
            onDismiss = { addToPlaylistTag = null },
        )
    }

    renameTag?.let { tag ->
        RenameTagDialog(
            initialName = tag.name,
            onDismiss = { renameTag = null },
            onRename = { name ->
                scope.launch { repo.renameTag(tag.id, name) }
                renameTag = null
            },
        )
    }

    deleteTag?.let { tag ->
        AlertDialog(
            onDismissRequest = { deleteTag = null },
            title = { Text("Delete mood?") },
            text = { Text("\"${tag.name}\" will be removed from all songs. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { repo.deleteTag(tag.id) }
                    deleteTag = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTag = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ManageRow(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    )
}

@Composable
private fun RenameTagDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename mood") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Mood name") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onRename(name.trim()) },
                enabled = name.isNotBlank(),
            ) { Text("Rename") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun CreateTagDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New mood") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Mood name") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onCreate(name.trim()) },
                enabled = name.isNotBlank(),
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MoodTile(
    name: String,
    count: Int,
    coverUris: List<String>,
    coverSize: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        PlaylistCollage(uris = coverUris.take(4), size = coverSize)
        Spacer(Modifier.height(6.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = if (count == 1) "1 song" else "$count songs",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
