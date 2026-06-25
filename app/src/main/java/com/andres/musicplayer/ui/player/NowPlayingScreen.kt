package com.andres.musicplayer.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.media3.common.Player
import com.andres.musicplayer.ui.art.SongArtwork
import com.andres.musicplayer.ui.art.SongArtworkGlow
import com.andres.musicplayer.ui.common.EmptyState
import com.andres.musicplayer.ui.songs.EditInfoDialog
import com.andres.musicplayer.ui.util.formatDuration

/** Full-screen player: blurred-art backdrop, live seek bar, full transport controls. */
@Composable
fun NowPlayingScreen(
    player: PlayerState,
    onBack: () -> Unit,
    onSaveInfo: (id: Long, title: String, artist: String?, album: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = player.current
    var scrubFraction by remember { mutableStateOf<Float?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var showSleepDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showQueueSheet by remember { mutableStateOf(false) }

    // Nothing playing: keep only the collapse affordance, then bail out before any
    // of the player layout (hoisted so we never `return` from inside a Column).
    if (current == null) {
        Box(modifier = modifier.fillMaxSize()) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
            ) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Collapse")
            }
            EmptyState(
                title = "Nothing playing",
                body = "Pick a song from your library.",
                modifier = Modifier.align(Alignment.Center),
            )
        }
        return
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
        ) {
            // Top row: collapse + overflow menu.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Collapse")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (player.sleepTimerMinutes != null) {
                        Text(
                            text = "⏱ ${formatDuration(player.sleepRemainingMs)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Up next") },
                            onClick = { menuOpen = false; showQueueSheet = true },
                        )
                        DropdownMenuItem(
                            text = { Text("Playback speed") },
                            onClick = { menuOpen = false; showSpeedDialog = true },
                        )
                        DropdownMenuItem(
                            text = { Text("Sleep timer") },
                            onClick = { menuOpen = false; showSleepDialog = true },
                        )
                        DropdownMenuItem(
                            text = { Text("Edit info") },
                            onClick = { menuOpen = false; showEditDialog = true },
                        )
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                // Blurred glow radiating from behind the crisp artwork's center.
                SongArtworkGlow(
                    uri = current.song.uri,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f),
                )
                SongArtwork(uri = current.song.uri, size = 280.dp)
            }

            Spacer(Modifier.height(28.dp))

            Text(
                text = current.song.title,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = current.song.artist ?: "Unknown artist",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.weight(1f))

            val duration = if (player.durationMs > 0) player.durationMs else current.song.durationMs
            val liveFraction = if (duration > 0) player.positionMs.toFloat() / duration else 0f
            val shownFraction = scrubFraction ?: liveFraction
            val shownPositionMs = scrubFraction?.let { (it * duration).toLong() } ?: player.positionMs

            SeekBar(
                fraction = shownFraction,
                onScrub = { scrubFraction = it },
                onScrubFinished = {
                    scrubFraction?.let { player.seekTo((it * duration).toLong()) }
                    scrubFraction = null
                },
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(formatDuration(shownPositionMs), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.weight(1f))
                Text(formatDuration(duration), style = MaterialTheme.typography.labelMedium)
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { player.toggleShuffle() }) {
                    Icon(
                        Icons.Default.Shuffle,
                        contentDescription = "Shuffle",
                        tint = if (player.shuffle) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                IconButton(onClick = { player.previous() }, modifier = Modifier.size(56.dp)) {
                    SkipPreviousIcon(Modifier.size(30.dp))
                }
                FilledIconButton(
                    onClick = { player.togglePlayPause() },
                    modifier = Modifier.size(72.dp),
                ) {
                    if (player.isPlaying) PauseIcon(Modifier.size(30.dp)) else PlayIcon(Modifier.size(30.dp))
                }
                IconButton(onClick = { player.next() }, modifier = Modifier.size(56.dp)) {
                    SkipNextIcon(Modifier.size(30.dp))
                }
                IconButton(onClick = { player.cycleRepeat() }) {
                    Icon(
                        imageVector = if (player.repeatMode == Player.REPEAT_MODE_ONE) {
                            Icons.Default.RepeatOne
                        } else {
                            Icons.Default.Repeat
                        },
                        contentDescription = "Repeat",
                        tint = if (player.repeatMode != Player.REPEAT_MODE_OFF) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showSpeedDialog) {
        OptionDialog(
            title = "Playback speed",
            options = speedOptions,
            isSelected = { it.value == player.speed },
            label = { it.label },
            onSelect = { player.changeSpeed(it.value); showSpeedDialog = false },
            onDismiss = { showSpeedDialog = false },
        )
    }
    if (showSleepDialog) {
        OptionDialog(
            title = "Sleep timer",
            options = sleepOptions,
            isSelected = { it.minutes == player.sleepTimerMinutes },
            label = { it.label },
            onSelect = { player.setSleepTimer(it.minutes); showSleepDialog = false },
            onDismiss = { showSleepDialog = false },
        )
    }
    if (showEditDialog) {
        EditInfoDialog(
            song = current.song,
            onDismiss = { showEditDialog = false },
            onSave = { title, artist, album ->
                onSaveInfo(current.song.id, title, artist, album)
                showEditDialog = false
            },
        )
    }
    if (showQueueSheet) {
        QueueSheet(player = player, onDismiss = { showQueueSheet = false })
    }
}

/** "Up next" — the live play queue. Long-press the drag handle to reorder, VLC-style. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueSheet(player: PlayerState, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val queue = player.queue
    val playingSongId = player.current?.song?.id

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Text(
            text = "Up next",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
        )
        if (queue.isEmpty()) {
            Text(
                text = "The queue is empty.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )
        } else {
            DraggableQueueList(
                queue = queue,
                playingSongId = playingSongId,
                onTap = { player.playQueueItem(it) },
                onRemove = { player.removeFromQueue(it) },
                onMove = { from, to -> player.moveQueueItem(from, to) },
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * A reorderable list: drag a row by its handle to move it. The visual reorder happens
 * on a local copy for smoothness; the net move is committed to the player on drop.
 */
@Composable
private fun DraggableQueueList(
    queue: List<com.andres.musicplayer.data.local.entity.SongWithTags>,
    playingSongId: Long?,
    onTap: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
) {
    val listState = rememberLazyListState()
    // Local working copy, rebuilt whenever the real queue changes (e.g. after a commit).
    val items = remember(queue) { queue.toMutableStateList() }
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var startIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffsetY by remember { mutableStateOf(0f) }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
    ) {
        itemsIndexed(items = items, key = { _, item -> item.song.id }) { index, item ->
            val liveIndex by rememberUpdatedState(index)
            val isDragging = index == draggingIndex
            val isCurrent = item.song.id == playingSongId
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .zIndex(if (isDragging) 1f else 0f)
                    .graphicsLayer {
                        translationY = if (isDragging) dragOffsetY else 0f
                        shadowElevation = if (isDragging) 12f else 0f
                    }
                    .background(
                        if (isDragging) {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        } else {
                            androidx.compose.ui.graphics.Color.Transparent
                        },
                    )
                    .clickable(enabled = draggingIndex == null) { onTap(liveIndex) }
                    .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SongArtwork(uri = item.song.uri, size = 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.song.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                        color = if (isCurrent) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = item.song.artist ?: "Unknown artist",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!isCurrent) {
                    IconButton(onClick = { onRemove(liveIndex) }) {
                        Icon(Icons.Default.Close, contentDescription = "Remove")
                    }
                } else {
                    Spacer(Modifier.size(48.dp))
                }
                Icon(
                    imageVector = Icons.Default.DragHandle,
                    contentDescription = "Drag to reorder",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(40.dp)
                        .padding(8.dp)
                        .pointerInput(Unit) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    draggingIndex = liveIndex
                                    startIndex = liveIndex
                                    dragOffsetY = 0f
                                },
                                onDragEnd = {
                                    val from = startIndex
                                    val to = draggingIndex
                                    if (from != null && to != null && from != to) onMove(from, to)
                                    draggingIndex = null
                                    startIndex = null
                                    dragOffsetY = 0f
                                },
                                onDragCancel = {
                                    draggingIndex = null
                                    startIndex = null
                                    dragOffsetY = 0f
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    dragOffsetY += dragAmount.y
                                    val cur = draggingIndex ?: return@detectDragGesturesAfterLongPress
                                    val height = listState.layoutInfo.visibleItemsInfo
                                        .find { it.index == cur }?.size ?: 0
                                    if (height <= 0) return@detectDragGesturesAfterLongPress
                                    if (dragOffsetY > height / 2 && cur < items.lastIndex) {
                                        items.add(cur + 1, items.removeAt(cur))
                                        draggingIndex = cur + 1
                                        dragOffsetY -= height
                                    } else if (dragOffsetY < -height / 2 && cur > 0) {
                                        items.add(cur - 1, items.removeAt(cur))
                                        draggingIndex = cur - 1
                                        dragOffsetY += height
                                    }
                                },
                            )
                        },
                )
            }
        }
    }
}

private data class SpeedOption(val label: String, val value: Float)

private val speedOptions = listOf(
    SpeedOption("0.5×", 0.5f),
    SpeedOption("0.75×", 0.75f),
    SpeedOption("Normal", 1f),
    SpeedOption("1.25×", 1.25f),
    SpeedOption("1.5×", 1.5f),
    SpeedOption("2×", 2f),
)

private data class SleepOption(val label: String, val minutes: Int?)

private val sleepOptions = listOf(
    SleepOption("Off", null),
    SleepOption("15 minutes", 15),
    SleepOption("30 minutes", 30),
    SleepOption("45 minutes", 45),
    SleepOption("1 hour", 60),
)

@Composable
private fun <T> OptionDialog(
    title: String,
    options: List<T>,
    isSelected: (T) -> Boolean,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(option) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = isSelected(option), onClick = null)
                        Spacer(Modifier.size(12.dp))
                        Text(label(option))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}
