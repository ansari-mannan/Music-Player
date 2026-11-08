package com.andres.musicplayer.ui.songs

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.andres.musicplayer.data.SongRepository
import com.andres.musicplayer.data.local.entity.Song
import com.andres.musicplayer.ui.art.ArtworkVersion
import com.andres.musicplayer.ui.common.LocalSongActions
import com.andres.musicplayer.ui.player.PlayerState
import com.andres.musicplayer.ui.playlists.AddToPlaylistDialog
import com.andres.musicplayer.ui.util.ArtSaveRequest
import com.andres.musicplayer.ui.util.rememberArtworkSaver
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Pixels per frame the list auto-scrolls while a dragged row is held near an edge. */
private const val EDGE_SCROLL_STEP = 14f

/** Lists shorter than this don't get a fast-scroll thumb; plain flinging is quicker. */
private const val FAST_SCROLL_MIN_ITEMS = 40

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
 *
 * Every row has a ⋮ menu with the same actions the selection bar offers, so nothing
 * depends on knowing about long-press.
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
    listState: LazyListState = rememberLazyListState(),
) {
    val scope = rememberCoroutineScope()
    val saveArtwork = rememberArtworkSaver()
    val songActions = LocalSongActions.current
    val multiSelect = SongListCapability.MULTI_SELECT in capabilities
    val canEdit = SongListCapability.EDIT_INFO in capabilities && repo != null
    val canDelete = SongListCapability.DELETE_FROM_LIBRARY in capabilities && repo != null
    val canRemove = SongListCapability.REMOVE_FROM_PLAYLIST in capabilities && onRemoveFromPlaylist != null
    val canQueue = SongListCapability.QUEUE_ACTIONS in capabilities
    val canAddToPlaylist = repo != null
    val canReorder = onMove != null

    val playlistsFlow = remember(repo) { repo?.playlistSummaries ?: emptyFlow() }
    val playlists by playlistsFlow.collectAsState(initial = emptyList())

    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var editSong by remember { mutableStateOf<Song?>(null) }
    var deleteTargets by remember { mutableStateOf<List<Song>>(emptyList()) }
    var playlistTargets by remember { mutableStateOf<List<Song>>(emptyList()) }
    val selectionMode = multiSelect && selectedIds.isNotEmpty()

    fun clearSelection() { selectedIds = emptySet() }
    val selectedSongs = songs.filter { it.id in selectedIds }

    // Back leaves selection mode before it leaves the screen.
    BackHandler(enabled = selectionMode) { clearSelection() }

    Column(modifier = modifier.fillMaxSize()) {
        if (selectionMode) {
            SelectionBar(
                count = selectedIds.size,
                singleSelected = selectedIds.size == 1,
                canAddToPlaylist = canAddToPlaylist,
                canEdit = canEdit,
                canDelete = canDelete,
                canRemove = canRemove,
                canQueue = canQueue,
                onClose = { clearSelection() },
                onSelectAll = { selectedIds = songs.map { it.id }.toSet() },
                onAddToPlaylist = { playlistTargets = selectedSongs },
                onSaveArtwork = {
                    saveArtwork(selectedSongs.map { ArtSaveRequest(it.uri, it.title) })
                    clearSelection()
                },
                onEdit = { editSong = selectedSongs.firstOrNull() },
                onDelete = { deleteTargets = selectedSongs },
                onRemove = {
                    selectedSongs.forEach { onRemoveFromPlaylist?.invoke(it.id) }
                    clearSelection()
                },
                onPlayNext = {
                    // Keep the list's order, so A, B, C plays as A, B, C.
                    player.playNext(selectedSongs)
                    clearSelection()
                },
                onAddToQueue = {
                    player.addToQueue(selectedSongs)
                    clearSelection()
                },
            )
        }

        // Local working copy: mirrors [songs], and is reordered live during a drag
        // (committed to the caller via onMove on drop). Rebuilt when songs changes.
        val items = remember(songs) { songs.toMutableStateList() }
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

        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 12.dp),
            ) {
                itemsIndexed(items = items, key = { _, item -> item.id }) { index, item ->
                    val liveIndex by rememberUpdatedState(index)
                    val isDragging = canReorder && index == draggingIndex
                    val isCurrent = player.current?.id == item.id
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
                        val menu: (@Composable () -> Unit)? = if (!selectionMode) {
                            {
                                RowMenu(
                                    canQueue = canQueue,
                                    canAddToPlaylist = canAddToPlaylist,
                                    canEdit = canEdit,
                                    canDelete = canDelete,
                                    canRemove = canRemove,
                                    onPlayNext = { player.playNext(item) },
                                    onAddToQueue = { player.addToQueue(item) },
                                    onAddToPlaylist = { playlistTargets = listOf(item) },
                                    onEdit = { editSong = item },
                                    onTrim = { songActions.openTrim(item) },
                                    onDelete = { deleteTargets = listOf(item) },
                                    onRemove = { onRemoveFromPlaylist?.invoke(item.id) },
                                )
                            }
                        } else {
                            null
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SongRow(
                                item = item,
                                isCurrent = isCurrent,
                                animate = isCurrent && player.isPlaying,
                                isSelected = item.id in selectedIds,
                                selectionMode = selectionMode,
                                onClick = rowOnClick,
                                onLongClick = rowOnLongClick,
                                modifier = Modifier.weight(1f),
                                trailing = menu,
                            )
                            if (canReorder && !selectionMode) {
                                // Handle is a SIBLING of the row, outside its clickable, so
                                // dragging it never triggers a tap or a selection.
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
                        }
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 76.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        )
                    }
                }
            }
            if (items.size >= FAST_SCROLL_MIN_ITEMS && draggingIndex == null) {
                FastScrollThumb(
                    listState = listState,
                    itemCount = items.size,
                    modifier = Modifier.align(Alignment.TopEnd),
                )
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
                    val ok = repo.writeArtworkFromUri(editing.id, imageUri)
                    if (ok) ArtworkVersion.bump(editing.uri)
                    ok
                },
            )
        }
    }

    if (playlistTargets.isNotEmpty() && repo != null) {
        // Captured now; the selection is cleared as soon as the dialog acts.
        val targetSongs = playlistTargets
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
                playlistTargets = emptyList()
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
                playlistTargets = emptyList()
            },
            onDismiss = { playlistTargets = emptyList() },
        )
    }

    if (deleteTargets.isNotEmpty() && repo != null) {
        val count = deleteTargets.size
        AlertDialog(
            onDismissRequest = { deleteTargets = emptyList() },
            title = { Text(if (count == 1) "Remove \"${deleteTargets.single().title}\"?" else "Remove $count songs?") },
            text = {
                Text(
                    "They'll be removed from your library and won't come back on the next " +
                        "scan. The audio files on your device aren't deleted. You can bring " +
                        "them back from Settings → Hidden songs.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val ids = deleteTargets.map { it.id }
                        scope.launch { repo.deleteSongs(ids) }
                        clearSelection()
                        deleteTargets = emptyList()
                    },
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTargets = emptyList() }) { Text("Cancel") }
            },
        )
    }
}

/**
 * A draggable thumb on the right edge: drag it to jump through a long list without
 * flinging. Sits over the list and only takes touches on the thumb itself.
 */
@Composable
private fun FastScrollThumb(
    listState: LazyListState,
    itemCount: Int,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val thumbHeight = 48.dp
    var dragging by remember { mutableStateOf(false) }
    val fraction by remember(itemCount) {
        derivedStateOf {
            val visible = listState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)
            val scrollable = (itemCount - visible).coerceAtLeast(1)
            (listState.firstVisibleItemIndex.toFloat() / scrollable).coerceIn(0f, 1f)
        }
    }
    BoxWithConstraints(modifier = modifier.fillMaxHeight().width(28.dp)) {
        val density = LocalDensity.current
        val trackPx = with(density) { (maxHeight - thumbHeight).toPx() }.coerceAtLeast(1f)
        Box(
            modifier = Modifier
                .offset { IntOffset(0, (fraction * trackPx).roundToInt()) }
                .padding(end = 4.dp)
                .align(Alignment.TopEnd)
                .size(width = if (dragging) 8.dp else 5.dp, height = thumbHeight)
                .clip(RoundedCornerShape(4.dp))
                .background(
                    MaterialTheme.colorScheme.primary.copy(alpha = if (dragging) 0.9f else 0.45f),
                )
                .pointerInput(itemCount, trackPx) {
                    var y = 0f
                    detectVerticalDragGestures(
                        onDragStart = {
                            dragging = true
                            y = fraction * trackPx
                        },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                        onVerticalDrag = { change, dy ->
                            change.consume()
                            y = (y + dy).coerceIn(0f, trackPx)
                            val target = ((y / trackPx) * (itemCount - 1)).roundToInt()
                            scope.launch { listState.scrollToItem(target) }
                        },
                    )
                },
        )
    }
}

/** A grip icon that starts a drag immediately (it has no other job), reporting vertical movement. */
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
            .size(44.dp)
            .padding(10.dp)
            .pointerInput(Unit) {
                detectDragGestures(
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

/** Per-row ⋮ menu: everything the selection bar can do, for one song. */
@Composable
private fun RowMenu(
    canQueue: Boolean,
    canAddToPlaylist: Boolean,
    canEdit: Boolean,
    canDelete: Boolean,
    canRemove: Boolean,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onEdit: () -> Unit,
    onTrim: () -> Unit,
    onDelete: () -> Unit,
    onRemove: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "More actions")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (canQueue) {
                DropdownMenuItem(text = { Text("Play next") }, onClick = { open = false; onPlayNext() })
                DropdownMenuItem(text = { Text("Add to queue") }, onClick = { open = false; onAddToQueue() })
            }
            if (canAddToPlaylist) {
                DropdownMenuItem(text = { Text("Add to playlist") }, onClick = { open = false; onAddToPlaylist() })
            }
            if (canEdit) {
                DropdownMenuItem(text = { Text("Edit info") }, onClick = { open = false; onEdit() })
                DropdownMenuItem(text = { Text("Trim / cut") }, onClick = { open = false; onTrim() })
            }
            if (canRemove) {
                DropdownMenuItem(text = { Text("Remove from playlist") }, onClick = { open = false; onRemove() })
            }
            if (canDelete) {
                DropdownMenuItem(text = { Text("Remove from library") }, onClick = { open = false; onDelete() })
            }
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
                .height(56.dp)
                .padding(horizontal = 4.dp),
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
                            text = { Text("Remove from library") },
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
