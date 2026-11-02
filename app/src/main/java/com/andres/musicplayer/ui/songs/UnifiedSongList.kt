package com.andres.musicplayer.ui.songs

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.andres.musicplayer.data.local.entity.Song
import com.andres.musicplayer.ui.art.ArtworkVersion
import com.andres.musicplayer.ui.player.PlayerState
import com.andres.musicplayer.ui.util.ArtSaveRequest
import com.andres.musicplayer.ui.util.rememberArtworkSaver
import com.andres.musicplayer.ui.playlists.AddToPlaylistDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Pixels per frame the list auto-scrolls while a dragged row is held near an edge. */
private const val EDGE_SCROLL_STEP = 14f

/** What a given song list is allowed to do. Action UI appears only for the caps present. */
enum class SongListCapability {
    PLAY,
    MULTI_SELECT,
    EDIT_INFO,
    DELETE_FROM_LIBRARY,
    REMOVE_FROM_PLAYLIST,
    QUEUE_ACTIONS,
}

/**
 * The single song-list used everywhere. Tap to play; the rest of the behaviour
 * (multi-select, edit, delete, remove-from-playlist, queue actions) is switched on
 * via [capabilities]. [repo] is required for the edit/delete/add-to-playlist
 * actions; [onRemoveFromPlaylist] for removal.
 */
@Composable
fun UnifiedSongList(
    songs: List<Song>,
    player: PlayerState,
    capabilities: Set<SongListCapability>,
    modifier: Modifier = Modifier,
    onRemoveFromPlaylist: ((songId: Long) -> Unit)? = null,
    repo: SongRepository? = null,
    // When true (Browse), songs aren't yet in the library, so adding to a playlist
    // imports them first to obtain real library ids.
    importForPlaylist: Boolean = false,
    // When provided (playlist detail), rows get a drag handle to reorder; the new
    // order (from → to within [songs]) is reported here for the caller to persist.
    onMove: ((from: Int, to: Int) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val saveArtwork = rememberArtworkSaver()
    val multiSelect = SongListCapability.MULTI_SELECT in capabilities
    val canEdit = SongListCapability.EDIT_INFO in capabilities && repo != null
    val canDelete = SongListCapability.DELETE_FROM_LIBRARY in capabilities && repo != null
    val canRemove = SongListCapability.REMOVE_FROM_PLAYLIST in capabilities && onRemoveFromPlaylist != null
    val canQueue = SongListCapability.QUEUE_ACTIONS in capabilities
    val canAddToPlaylist = repo != null
    val canReorder = onMove != null

    val playlistsFlow = remember(repo) { repo?.allPlaylistsWithSongs ?: emptyFlow() }
    val playlists by playlistsFlow.collectAsState(initial = emptyList())

    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var editSong by remember { mutableStateOf<Song?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showPlaylistPicker by remember { mutableStateOf(false) }
    val selectionMode = multiSelect && selectedIds.isNotEmpty()

    fun clearSelection() { selectedIds = emptySet() }
    val selectedSongs = songs.filter { it.id in selectedIds }

    Column(modifier = modifier.fillMaxSize()) {
        if (selectionMode) {
            SelectionBar(
                count = selectedIds.size,
                singleSelected = selectedIds.size == 1,
                canAddToPlaylist = canAddToPlaylist,
                canEdit = canEdit,
                canDelete = canDelete,
                canRemove = canRemove,
                canQueue = canQueue && player.current != null,
                onClose = { clearSelection() },
                onSelectAll = { selectedIds = songs.map { it.id }.toSet() },
                onAddToPlaylist = { showPlaylistPicker = true },
                onSaveArtwork = {
                    saveArtwork(selectedSongs.map { ArtSaveRequest(it.uri, it.title) })
                    clearSelection()
                },
                onEdit = { editSong = selectedSongs.firstOrNull() },
                onDelete = { showDeleteConfirm = true },
                onRemove = {
                    selectedSongs.forEach { onRemoveFromPlaylist?.invoke(it.id) }
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

        // Swaps the dragged row with its neighbour once it's been dragged past half a
        // row's height — shared by the pointer drag and the edge auto-scroll below.
        fun trySwap() {
            val cur = draggingIndex ?: return
            val h = listState.layoutInfo.visibleItemsInfo.find { it.index == cur }?.size ?: return
            if (h <= 0) return
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

        // While a row is held near the top/bottom edge, keep scrolling the list so the
        // user can drag a song all the way up/down in one motion (not in stops).
        LaunchedEffect(draggingIndex) {
            if (draggingIndex == null) return@LaunchedEffect
            while (isActive && draggingIndex != null) {
                val cur = draggingIndex
                val info = listState.layoutInfo
                val item = info.visibleItemsInfo.find { it.index == cur }
                if (item != null) {
                    val edge = item.size.coerceAtMost(140)
                    val top = item.offset + dragOffsetY
                    val bottom = top + item.size
                    val delta = when {
                        top < info.viewportStartOffset + edge -> -EDGE_SCROLL_STEP
                        bottom > info.viewportEndOffset - edge -> EDGE_SCROLL_STEP
                        else -> 0f
                    }
                    if (delta != 0f) {
                        val consumed = listState.scrollBy(delta)
                        dragOffsetY += consumed
                        trySwap()
                    }
                }
                delay(16)
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 12.dp),
        ) {
            itemsIndexed(items = items, key = { _, item -> item.id }) { index, item ->
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
                            selectedIds = selectedIds.toggle(item.id)
                        } else {
                            player.play(item, items)
                        }
                    }
                    val rowOnLongClick = {
                        if (multiSelect) selectedIds = selectedIds + item.id
                    }
                    if (canReorder && !selectionMode) {
                        // Handle is a SIBLING of the row, outside its clickable, so a
                        // long-press on it starts a drag without triggering selection.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SongRow(
                                item = item,
                                isPlaying = player.current?.id == item.id,
                                isSelected = item.id in selectedIds,
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
                                    trySwap()
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
                            isPlaying = player.current?.id == item.id,
                            isSelected = item.id in selectedIds,
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

    if (repo != null) {
        editSong?.let { editing ->
            EditInfoDialog(
                song = editing,
                onDismiss = { editSong = null },
                onSave = { title, artist, album ->
                    scope.launch {
                        repo.updateSongInfo(editing.id, title, artist, album)
                        repo.writeMetadataToFile(editing.id)
                    }
                    editSong = null
                    clearSelection()
                },
                onChangeArtwork = { imageUri ->
                    scope.launch {
                        if (repo.writeArtworkFromUri(editing.id, imageUri)) ArtworkVersion.bump()
                    }
                },
            )
        }
    }

    if (showPlaylistPicker && repo != null) {
        // Capture the songs now; selection is cleared as soon as the dialog acts.
        val targetSongs = selectedSongs
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
private fun RowQueueMenu(player: PlayerState, item: Song) {
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
    canEdit: Boolean,
    canDelete: Boolean,
    canRemove: Boolean,
    canQueue: Boolean,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onSaveArtwork: () -> Unit,
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
                    // Saving cover art is universally applicable, so always offered.
                    DropdownMenuItem(
                        text = { Text(if (singleSelected) "Save artwork" else "Save artwork ($count)") },
                        onClick = { menuOpen = false; onSaveArtwork() },
                    )
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
