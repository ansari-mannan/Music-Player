package com.andres.musicplayer.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.material.icons.automirrored.filled.QueueMusic
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.media3.common.Player
import com.andres.musicplayer.ui.art.SongArtwork
import com.andres.musicplayer.ui.art.SongArtworkGlow
import com.andres.musicplayer.ui.common.EmptyState
import com.andres.musicplayer.ui.common.LocalSongActions
import com.andres.musicplayer.ui.songs.EditInfoDialog
import com.andres.musicplayer.ui.util.ArtSaveRequest
import com.andres.musicplayer.ui.util.formatDuration
import com.andres.musicplayer.ui.util.rememberArtworkSaver
import com.andres.musicplayer.ui.util.rememberMonoController
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Pixels per frame the queue auto-scrolls while a dragged row is held near an edge. */
private const val QUEUE_EDGE_SCROLL_STEP = 14f

/** Full-screen player: blurred-art backdrop, live seek bar, full transport controls. */
@Composable
fun NowPlayingScreen(
    player: PlayerState,
    onBack: () -> Unit,
    onSaveInfo: (id: Long, title: String, artist: String?, album: String?) -> Unit,
    onChangeArtwork: suspend (id: Long, imageUri: android.net.Uri) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val current = player.current
    val songActions = LocalSongActions.current
    val mono = rememberMonoController()
    val saveArtwork = rememberArtworkSaver()

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

    val collapseThresholdPx = with(LocalDensity.current) { 120.dp.toPx() }
    Box(
        modifier = modifier
            .fillMaxSize()
            // Swipe down anywhere to collapse, like most players. Horizontal gestures
            // (artwork swipe, seek bar) claim their drags first, so they're unaffected.
            .pointerInput(Unit) {
                var totalDy = 0f
                detectVerticalDragGestures(
                    onDragStart = { totalDy = 0f },
                    onVerticalDrag = { _, dy -> totalDy += dy },
                    onDragEnd = { if (totalDy > collapseThresholdPx) onBack() },
                )
            },
    ) {
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
                    IconButton(onClick = { showQueueSheet = true }) {
                        Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = "Up next")
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Playback speed") },
                            onClick = { menuOpen = false; showSpeedDialog = true },
                        )
                        DropdownMenuItem(
                            text = { Text("Sleep timer") },
                            onClick = { menuOpen = false; showSleepDialog = true },
                        )
                        DropdownMenuItem(
                            text = { Text(if (mono.isOn) "Mono audio: On" else "Mono audio: Off") },
                            onClick = { menuOpen = false; mono.toggle() },
                        )
                        DropdownMenuItem(
                            text = { Text("Save artwork") },
                            onClick = {
                                menuOpen = false
                                saveArtwork(listOf(ArtSaveRequest(current.uri, current.title)))
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Edit info") },
                            onClick = { menuOpen = false; showEditDialog = true },
                        )
                        if (current.id > 0) {
                            DropdownMenuItem(
                                text = { Text("Trim / cut") },
                                onClick = { menuOpen = false; songActions.openTrim(current) },
                            )
                        }
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // Swipe the artwork left/right to go to the next/previous track.
                    .pointerInput(current.id) {
                        var totalDx = 0f
                        val threshold = size.width * 0.18f
                        detectHorizontalDragGestures(
                            onDragStart = { totalDx = 0f },
                            onHorizontalDrag = { _, dragAmount -> totalDx += dragAmount },
                            onDragEnd = {
                                when {
                                    totalDx <= -threshold -> player.next()
                                    totalDx >= threshold -> player.previous()
                                }
                                totalDx = 0f
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                // Blurred glow radiating from behind the crisp artwork's center.
                SongArtworkGlow(
                    uri = current.uri,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f),
                )
                SongArtwork(uri = current.uri, size = 280.dp)
            }

            Spacer(Modifier.height(28.dp))

            Text(
                text = current.title,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = current.artist ?: "Unknown artist",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.weight(1f))

            val duration = if (player.durationMs > 0) player.durationMs else current.durationMs
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
                    if (player.showPlayButton) PlayIcon(Modifier.size(30.dp)) else PauseIcon(Modifier.size(30.dp))
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
            isSelected = { it.minutes == player.sleepTimerMinutes ||
                (it.minutes == null && player.sleepTimerMinutes == null) },
            label = { it.label },
            onSelect = { player.setSleepTimer(it.minutes); showSleepDialog = false },
            onDismiss = { showSleepDialog = false },
        )
    }
    if (showEditDialog) {
        EditInfoDialog(
            song = current,
            onDismiss = { showEditDialog = false },
            onSave = { title, artist, album ->
                onSaveInfo(current.id, title, artist, album)
                showEditDialog = false
            },
            onChangeArtwork = { imageUri -> onChangeArtwork(current.id, imageUri) },
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
    val playingSongId = player.current?.id

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
                currentIndex = player.currentIndex,
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
    queue: List<com.andres.musicplayer.data.local.entity.Song>,
    playingSongId: Long?,
    currentIndex: Int,
    onTap: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
) {
    // Opens on the playing song, with a little of what came before above it.
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (currentIndex - 1).coerceAtLeast(0))
    // Local working copy, rebuilt whenever the real queue changes (e.g. after a commit).
    // Each row carries its original position as its key: the same song can be queued
    // twice, and two rows sharing a key crashed the list.
    val items = remember(queue) { queue.mapIndexed { i, song -> QueueRow(i, song) }.toMutableStateList() }
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var startIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffsetY by remember { mutableStateOf(0f) }

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

    // Auto-scroll the queue while a row is held near an edge so it can be dragged the
    // whole way in one motion instead of in stops.
    LaunchedEffect(draggingIndex) {
        if (draggingIndex == null) return@LaunchedEffect
        while (isActive && draggingIndex != null) {
            val cur = draggingIndex
            val info = listState.layoutInfo
            val item = info.visibleItemsInfo.find { it.index == cur }
            if (item != null) {
                val edge = item.size.coerceAtMost(120)
                val top = item.offset + dragOffsetY
                val bottom = top + item.size
                val delta = when {
                    top < info.viewportStartOffset + edge -> -QUEUE_EDGE_SCROLL_STEP
                    bottom > info.viewportEndOffset - edge -> QUEUE_EDGE_SCROLL_STEP
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
        modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
    ) {
        itemsIndexed(items = items, key = { _, row -> row.key }) { index, row ->
            val item = row.song
            val liveIndex by rememberUpdatedState(index)
            val isDragging = index == draggingIndex
            val isCurrent = row.key == currentIndex && item.id == playingSongId
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
                SongArtwork(uri = item.uri, size = 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
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
                        text = item.artist ?: "Unknown artist",
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
                            // The handle exists only to drag, so it starts immediately.
                            detectDragGestures(
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
                                    trySwap()
                                },
                            )
                        },
                )
            }
        }
    }
}

/** A queue entry plus a key that stays unique even when a song is queued twice. */
private data class QueueRow(val key: Int, val song: com.andres.musicplayer.data.local.entity.Song)

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
