package com.andres.musicplayer.ui.songs

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.andres.musicplayer.data.SongRepository
import com.andres.musicplayer.data.local.entity.SongWithTags
import com.andres.musicplayer.data.local.entity.Tag
import com.andres.musicplayer.ui.player.PlayerState
import com.andres.musicplayer.ui.playlists.AddToPlaylistDialog
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

/** What a given song list is allowed to do. Action UI appears only for the caps present. */
enum class SongListCapability {
    PLAY,
    MULTI_SELECT,
    TAG,
    EDIT_INFO,
    DELETE_FROM_LIBRARY,
    REMOVE_FROM_PLAYLIST,
    QUEUE_ACTIONS,
}

/**
 * The single song-list used everywhere. Tap to play; the rest of the behaviour
 * (multi-select, tag, edit, delete, remove-from-playlist, queue actions) is
 * switched on via [capabilities]. [repo]/[allTags] are required for the
 * tag/edit/delete/add-to-playlist actions; [onRemoveFromPlaylist] for removal.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnifiedSongList(
    songs: List<SongWithTags>,
    player: PlayerState,
    capabilities: Set<SongListCapability>,
    modifier: Modifier = Modifier,
    onRemoveFromPlaylist: ((songId: Long) -> Unit)? = null,
    repo: SongRepository? = null,
    allTags: List<Tag> = emptyList(),
    // When true (Browse), songs aren't yet in the library, so adding to a playlist
    // imports them first to obtain real library ids.
    importForPlaylist: Boolean = false,
    // When provided (playlist detail), rows get a drag handle to reorder; the new
    // order (from → to within [songs]) is reported here for the caller to persist.
    onMove: ((from: Int, to: Int) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val multiSelect = SongListCapability.MULTI_SELECT in capabilities
    val canTag = SongListCapability.TAG in capabilities && repo != null
    val canEdit = SongListCapability.EDIT_INFO in capabilities && repo != null
    val canDelete = SongListCapability.DELETE_FROM_LIBRARY in capabilities && repo != null
    val canRemove = SongListCapability.REMOVE_FROM_PLAYLIST in capabilities && onRemoveFromPlaylist != null
    val canQueue = SongListCapability.QUEUE_ACTIONS in capabilities
    val canAddToPlaylist = repo != null
    val canReorder = onMove != null

    val playlistsFlow = remember(repo) { repo?.allPlaylistsWithSongs ?: emptyFlow() }
    val playlists by playlistsFlow.collectAsState(initial = emptyList())

    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showTagSheet by remember { mutableStateOf(false) }
    var editSong by remember { mutableStateOf<SongWithTags?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showPlaylistPicker by remember { mutableStateOf(false) }
    val selectionMode = multiSelect && selectedIds.isNotEmpty()

    fun clearSelection() { selectedIds = emptySet() }
    val selectedSongs = songs.filter { it.song.id in selectedIds }

    Column(modifier = modifier.fillMaxSize()) {
        if (selectionMode) {
            SelectionBar(
                count = selectedIds.size,
                singleSelected = selectedIds.size == 1,
                canAddToPlaylist = canAddToPlaylist,
                canTag = canTag,
                canEdit = canEdit,
                canDelete = canDelete,
                canRemove = canRemove,
                canQueue = canQueue && player.current != null,
                onClose = { clearSelection() },
                onSelectAll = { selectedIds = songs.map { it.song.id }.toSet() },
                onAddToPlaylist = { showPlaylistPicker = true },
                onTag = { showTagSheet = true },
                onEdit = { editSong = selectedSongs.firstOrNull() },
                onDelete = { showDeleteConfirm = true },
                onRemove = {
                    selectedSongs.forEach { onRemoveFromPlaylist?.invoke(it.song.id) }
                    clearSelection()
                },
                onPlayNext = {
                    selectedSongs.forEach { player.playNext(it) }
                    clearSelection()
                },
                onAddToQueue = {
                    selectedSongs.forEach { player.addToQueue(it) }
                    clearSelection()
                },
            )
        }

        // Local working copy: mirrors [songs], and is reordered live during a drag
        // (committed to the caller via onMove on drop). Rebuilt when songs changes.
        val items = remember(songs) { songs.toMutableStateList() }
        val listState = rememberLazyListState()
        var draggingIndex by remember { mutableStateOf<Int?>(null) }
        var startIndex by remember { mutableStateOf<Int?>(null) }
        var dragOffsetY by remember { mutableStateOf(0f) }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 12.dp),
        ) {
            itemsIndexed(items = items, key = { _, item -> item.song.id }) { index, item ->
                val liveIndex by rememberUpdatedState(index)
                val isDragging = canReorder && index == draggingIndex
                Column(
                    modifier = Modifier
                        .zIndex(if (isDragging) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (isDragging) dragOffsetY else 0f
                            shadowElevation = if (isDragging) 12f else 0f
                        }
                        .background(
                            if (isDragging) {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            } else {
                                Color.Transparent
                            },
                        ),
                ) {
                    val rowOnClick = {
                        if (selectionMode) {
                            selectedIds = selectedIds.toggle(item.song.id)
                        } else {
                            player.play(item, items)
                        }
                    }
                    val rowOnLongClick = {
                        if (multiSelect) selectedIds = selectedIds + item.song.id
                    }
                    if (canReorder && !selectionMode) {
                        // Handle is a SIBLING of the row, outside its clickable, so a
                        // long-press on it starts a drag without triggering selection.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SongRow(
                                item = item,
                                isPlaying = player.current?.song?.id == item.song.id,
                                isSelected = item.song.id in selectedIds,
                                selectionMode = selectionMode,
                                onClick = rowOnClick,
                                onLongClick = rowOnLongClick,
                                modifier = Modifier.weight(1f),
                            )
                            DragHandle(
                                onDragStart = {
                                    draggingIndex = liveIndex
                                    startIndex = liveIndex
                                    dragOffsetY = 0f
                                },
                                onDrag = { dy ->
                                    dragOffsetY += dy
                                    val cur = draggingIndex
                                    if (cur != null) {
                                        val h = listState.layoutInfo.visibleItemsInfo
                                            .find { it.index == cur }?.size ?: 0
                                        if (h > 0) {
                                            if (dragOffsetY > h / 2 && cur < items.lastIndex) {
                                                items.add(cur + 1, items.removeAt(cur))
                                                draggingIndex = cur + 1
                                                dragOffsetY -= h
                                            } else if (dragOffsetY < -h / 2 && cur > 0) {
                                                items.add(cur - 1, items.removeAt(cur))
                                                draggingIndex = cur - 1
                                                dragOffsetY += h
                                            }
                                        }
                                    }
                                },
                                onDragEnd = {
                                    val from = startIndex
                                    val to = draggingIndex
                                    if (from != null && to != null && from != to) {
                                        onMove?.invoke(from, to)
                                    }
                                    draggingIndex = null
                                    startIndex = null
                                    dragOffsetY = 0f
                                },
                            )
                        }
                    } else {
                        SongRow(
                            item = item,
                            isPlaying = player.current?.song?.id == item.song.id,
                            isSelected = item.song.id in selectedIds,
                            selectionMode = selectionMode,
                            onClick = rowOnClick,
                            onLongClick = rowOnLongClick,
                            trailing = if (canQueue && !selectionMode && player.current != null) {
                                { RowQueueMenu(player = player, item = item) }
                            } else {
                                null
                            },
                        )
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 76.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    )
                }
            }
        }
    }

    // Tag editor for the current selection (1 or many songs).
    if (showTagSheet && repo != null) {
        if (selectedSongs.isEmpty()) {
            showTagSheet = false
        } else {
            val appliedToAll = remember(selectedSongs, allTags) {
                allTags.filter { tag -> selectedSongs.all { s -> s.tags.any { it.id == tag.id } } }
                    .map { it.id }
                    .toSet()
            }
            val sheetState = rememberModalBottomSheetState()
            ModalBottomSheet(
                onDismissRequest = {
                    showTagSheet = false
                    // Persist the moods we just changed into each song's file.
                    val ids = selectedSongs.map { it.song.id }
                    scope.launch { ids.forEach { repo.writeMetadataToFile(it) } }
                },
                sheetState = sheetState,
            ) {
                TagSheetContent(
                    title = if (selectedSongs.size == 1) selectedSongs.first().song.title else "${selectedSongs.size} songs",
                    subtitle = if (selectedSongs.size == 1) {
                        selectedSongs.first().song.artist ?: "Unknown artist"
                    } else {
                        "Apply tags to all selected"
                    },
                    allTags = allTags,
                    appliedTagIds = appliedToAll,
                    onToggleTag = { tagId, isAppliedToAll ->
                        scope.launch {
                            selectedSongs.forEach { s ->
                                if (isAppliedToAll) repo.untagSong(s.song.id, tagId)
                                else repo.tagSong(s.song.id, tagId)
                            }
                        }
                    },
                    onCreateTag = { name ->
                        if (name.isNotBlank()) {
                            scope.launch {
                                val newId = repo.insertTag(Tag(name = name))
                                val tagId = if (newId != -1L) newId else repo.getTagByName(name)?.id
                                if (tagId != null) selectedSongs.forEach { repo.tagSong(it.song.id, tagId) }
                            }
                        }
                    },
                )
            }
        }
    }

    if (repo != null) {
        editSong?.let { editing ->
            EditInfoDialog(
                song = editing.song,
                onDismiss = { editSong = null },
                onSave = { title, artist, album ->
                    scope.launch {
                        repo.updateSongInfo(editing.song.id, title, artist, album)
                        repo.writeMetadataToFile(editing.song.id)
                    }
                    editSong = null
                    clearSelection()
                },
            )
        }
    }

    if (showPlaylistPicker && repo != null) {
        // Capture the songs now; selection is cleared as soon as the dialog acts.
        val targetSongs = selectedSongs.map { it.song }
        AddToPlaylistDialog(
            playlists = playlists,
            onPick = { playlistId ->
                scope.launch {
                    val ids = if (importForPlaylist) {
                        repo.importAndGetIds(targetSongs)
                    } else {
                        targetSongs.map { it.id }
                    }
                    repo.addSongsToPlaylist(playlistId, ids)
                }
                clearSelection()
                showPlaylistPicker = false
            },
            onCreateNew = { name ->
                scope.launch {
                    val ids = if (importForPlaylist) {
                        repo.importAndGetIds(targetSongs)
                    } else {
                        targetSongs.map { it.id }
                    }
                    val id = repo.createPlaylist(name)
                    repo.addSongsToPlaylist(id, ids)
                }
                clearSelection()
                showPlaylistPicker = false
            },
            onDismiss = { showPlaylistPicker = false },
        )
    }

    if (showDeleteConfirm && repo != null) {
        val count = selectedIds.size
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete $count song${if (count == 1) "" else "s"}?") },
            text = {
                Text("They'll be removed from your library. The audio files on your device aren't deleted.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val ids = selectedIds.toList()
                        scope.launch { repo.deleteSongs(ids) }
                        clearSelection()
                        showDeleteConfirm = false
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

/** A grip icon that starts a long-press drag, reporting vertical movement. */
@Composable
private fun DragHandle(
    onDragStart: () -> Unit,
    onDrag: (dy: Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    Icon(
        imageVector = Icons.Default.DragHandle,
        contentDescription = "Drag to reorder",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .size(40.dp)
            .padding(8.dp)
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { onDragStart() },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount.y)
                    },
                )
            },
    )
}

/** Per-row overflow menu offering the queue actions when something is playing. */
@Composable
private fun RowQueueMenu(player: PlayerState, item: SongWithTags) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "More actions")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Play next") },
                onClick = { open = false; player.playNext(item) },
            )
            DropdownMenuItem(
                text = { Text("Add to queue") },
                onClick = { open = false; player.addToQueue(item) },
            )
        }
    }
}

@Composable
private fun SelectionBar(
    count: Int,
    singleSelected: Boolean,
    canAddToPlaylist: Boolean,
    canTag: Boolean,
    canEdit: Boolean,
    canDelete: Boolean,
    canRemove: Boolean,
    canQueue: Boolean,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onTag: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onRemove: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 2.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Cancel selection")
            }
            Text(
                text = "$count selected",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onSelectAll) {
                Icon(Icons.Default.SelectAll, contentDescription = "Select all")
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More actions")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (canQueue) {
                        DropdownMenuItem(
                            text = { Text("Play next") },
                            onClick = { menuOpen = false; onPlayNext() },
                        )
                        DropdownMenuItem(
                            text = { Text("Add to queue") },
                            onClick = { menuOpen = false; onAddToQueue() },
                        )
                    }
                    if (canAddToPlaylist) {
                        DropdownMenuItem(
                            text = { Text("Add to playlist") },
                            onClick = { menuOpen = false; onAddToPlaylist() },
                        )
                    }
                    if (canTag) {
                        DropdownMenuItem(
                            text = { Text("Tag") },
                            onClick = { menuOpen = false; onTag() },
                        )
                    }
                    if (canEdit && singleSelected) {
                        DropdownMenuItem(
                            text = { Text("Edit info") },
                            onClick = { menuOpen = false; onEdit() },
                        )
                    }
                    if (canRemove) {
                        DropdownMenuItem(
                            text = { Text("Remove from playlist") },
                            onClick = { menuOpen = false; onRemove() },
                        )
                    }
                    if (canDelete) {
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            onClick = { menuOpen = false; onDelete() },
                        )
                    }
                }
            }
        }
    }
}

private fun Set<Long>.toggle(id: Long): Set<Long> =
    if (id in this) this - id else this + id
