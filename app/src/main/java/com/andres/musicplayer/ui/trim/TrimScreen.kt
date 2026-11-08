package com.andres.musicplayer.ui.trim

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.andres.musicplayer.data.SongRepository
import com.andres.musicplayer.data.local.entity.Song
import com.andres.musicplayer.data.trim.AudioTrimmer
import com.andres.musicplayer.data.trim.TrimMode
import com.andres.musicplayer.data.trim.TrimPlan
import com.andres.musicplayer.data.trim.Waveform
import com.andres.musicplayer.ui.common.EmptyState
import com.andres.musicplayer.ui.common.LoadingState
import com.andres.musicplayer.ui.player.PlayerState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

private enum class Handle { START, END }

/** How far before a cut the Cut-mode preview starts, so you hear the join in context. */
private const val CUT_PREVIEW_LEAD_MS = 3_000L

/**
 * Trim / cut editor for one song.
 *
 * Drag the two handles over the waveform (or tap the waveform to listen from a point and
 * "Set start/end here"), then either keep the selection — chopping off crowd noise and
 * chatter at both ends — or cut it out of the middle. The result replaces the file; the
 * original is kept so it can be undone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrimScreen(
    repo: SongRepository,
    player: PlayerState,
    songId: Long,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var song by remember { mutableStateOf<Song?>(null) }
    var waveform by remember { mutableStateOf<Waveform?>(null) }
    var waveDone by remember { mutableStateOf(false) }
    var loadFailed by remember { mutableStateOf(false) }
    var canUndo by remember { mutableStateOf(false) }

    LaunchedEffect(songId) {
        val s = repo.songsByIds(listOf(songId)).firstOrNull()
        song = s
        if (s == null) {
            loadFailed = true
            return@LaunchedEffect
        }
        canUndo = repo.hasTrimBackup(songId)
        // Partial pictures arrive while it's still reading, so the editor is usable early.
        val full = AudioTrimmer.waveform(context, Uri.parse(s.uri)) { partial -> waveform = partial }
        if (full == null) loadFailed = true else waveform = full
        waveDone = true
    }

    val current = song
    val wave = waveform
    when {
        loadFailed -> {
            EmptyState(
                title = "Can't open this song",
                body = "The file couldn't be read, so it can't be trimmed.",
            )
            return
        }
        current == null || wave == null -> {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(120.dp))
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text("Reading the waveform…", style = MaterialTheme.typography.bodyMedium)
            }
            return
        }
    }
    val durationMs = wave!!.durationMs

    var mode by remember { mutableStateOf(TrimMode.KEEP) }
    var startMs by remember { mutableLongStateOf(0L) }
    var endMs by remember { mutableLongStateOf(durationMs) }
    var active by remember { mutableStateOf(Handle.START) }
    var playheadMs by remember { mutableLongStateOf(0L) }
    var previewing by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var saveProgress by remember { mutableStateOf<Int?>(null) }

    // A private player for previews, so the main queue isn't disturbed.
    val preview = remember(current!!.uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(current.uri))
            prepare()
        }
    }
    DisposableEffect(preview) { onDispose { preview.release() } }

    val latestStart by rememberUpdatedState(startMs)
    val latestEnd by rememberUpdatedState(endMs)
    val latestMode by rememberUpdatedState(mode)

    // Follow the preview: move the playhead, and in Cut mode jump over the cut.
    LaunchedEffect(preview) {
        while (isActive) {
            if (preview.isPlaying) {
                val pos = preview.currentPosition
                playheadMs = pos
                if (latestMode == TrimMode.CUT && pos in latestStart until latestEnd) {
                    preview.seekTo(latestEnd)
                }
                if (latestMode == TrimMode.KEEP && pos >= latestEnd) {
                    preview.pause()
                }
            }
            previewing = preview.isPlaying
            delay(30)
        }
    }

    fun pauseMainPlayer() {
        if (player.isPlaying) player.togglePlayPause()
    }

    fun playFrom(ms: Long) {
        pauseMainPlayer()
        preview.seekTo(ms.coerceIn(0L, durationMs))
        preview.play()
    }

    fun previewResult() {
        when (mode) {
            TrimMode.KEEP -> playFrom(startMs)
            TrimMode.CUT -> playFrom((startMs - CUT_PREVIEW_LEAD_MS).coerceAtLeast(0L))
        }
    }

    fun setHandle(handle: Handle, ms: Long) {
        when (handle) {
            Handle.START -> startMs = ms.coerceIn(0L, (endMs - 100).coerceAtLeast(0L))
            Handle.END -> endMs = ms.coerceIn((startMs + 100).coerceAtMost(durationMs), durationMs)
        }
    }

    val plan = TrimPlan(mode, startMs, endMs)
    val resultMs = AudioTrimmer.resultDurationMs(plan, durationMs)
    val unchanged = resultMs >= durationMs - 50

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            current.title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "${current.artist ?: "Unknown artist"} · ${formatPrecise(durationMs)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = mode == TrimMode.KEEP,
                onClick = { mode = TrimMode.KEEP },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
            ) { Text("Keep selection") }
            SegmentedButton(
                selected = mode == TrimMode.CUT,
                onClick = { mode = TrimMode.CUT },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
            ) { Text("Cut selection") }
        }
        Text(
            text = if (mode == TrimMode.KEEP) {
                "Everything outside the handles is removed — for chopping off the start and end."
            } else {
                "Everything between the handles is removed — for cutting a chunk out of the middle."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )

        Spacer(Modifier.height(16.dp))
        WaveformEditor(
            waveform = wave,
            mode = mode,
            startMs = startMs,
            endMs = endMs,
            playheadMs = playheadMs,
            active = active,
            onDragHandle = { handle, ms ->
                active = handle
                setHandle(handle, ms)
            },
            onTap = { ms ->
                playheadMs = ms
                playFrom(ms)
            },
        )
        if (!waveDone) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text("0:00", style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.weight(1f))
            Text(formatPrecise(durationMs), style = MaterialTheme.typography.labelSmall)
        }
        Text(
            "Tap the waveform to listen from that point. Drag a handle to move it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))
        // Which handle the fine controls move.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = active == Handle.START,
                onClick = { active = Handle.START },
                label = { Text("Start  ${formatPrecise(startMs)}") },
            )
            FilterChip(
                selected = active == Handle.END,
                onClick = { active = Handle.END },
                label = { Text("End  ${formatPrecise(endMs)}") },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            listOf(-1000L to "−1s", -100L to "−0.1s", 100L to "+0.1s", 1000L to "+1s").forEach { (delta, label) ->
                OutlinedButton(
                    onClick = {
                        val base = if (active == Handle.START) startMs else endMs
                        setHandle(active, base + delta)
                    },
                ) { Text(label) }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(
                onClick = { setHandle(Handle.START, playheadMs); active = Handle.START },
                modifier = Modifier.weight(1f),
            ) { Text("Start here") }
            FilledTonalButton(
                onClick = { setHandle(Handle.END, playheadMs); active = Handle.END },
                modifier = Modifier.weight(1f),
            ) { Text("End here") }
        }
        Text(
            "Playhead ${formatPrecise(playheadMs)}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 4.dp),
        )

        Spacer(Modifier.height(16.dp))
        Text(
            "New length ${formatPrecise(resultMs)} (was ${formatPrecise(durationMs)})",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { if (previewing) preview.pause() else previewResult() },
                modifier = Modifier.weight(1f),
            ) { Text(if (previewing) "Stop" else "Preview") }
            Button(
                onClick = { preview.pause(); confirm = true },
                enabled = !unchanged && resultMs > 0 && saveProgress == null,
                modifier = Modifier.weight(1f),
            ) { Text("Save") }
        }
        if (canUndo) {
            TextButton(
                onClick = {
                    preview.pause()
                    scope.launch {
                        val outcome = repo.undoTrim(songId)
                        Toast.makeText(context, outcome.message, Toast.LENGTH_SHORT).show()
                        if (outcome.ok) onDone()
                    }
                },
                modifier = Modifier.padding(top = 8.dp),
            ) { Text("Undo last trim (restore original)") }
        }
        Text(
            "Saving replaces the file. The original is kept so the last trim can be undone. " +
                "The result is saved as AAC (.m4a) at high quality.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(if (mode == TrimMode.KEEP) "Keep the selection?" else "Cut the selection?") },
            text = {
                Text(
                    if (mode == TrimMode.KEEP) {
                        "Keeps ${formatPrecise(startMs)}–${formatPrecise(endMs)}. New length ${formatPrecise(resultMs)}."
                    } else {
                        "Removes ${formatPrecise(startMs)}–${formatPrecise(endMs)}. New length ${formatPrecise(resultMs)}."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    saveProgress = 0
                    scope.launch {
                        val outcome = try {
                            val file = AudioTrimmer.export(context, Uri.parse(current.uri), durationMs, plan) {
                                saveProgress = it
                            }
                            preview.stop()
                            repo.applyTrim(songId, file)
                        } catch (e: Exception) {
                            SongRepository.TrimOutcome(false, "Trim failed: ${e.message ?: "unknown error"}")
                        }
                        saveProgress = null
                        Toast.makeText(context, outcome.message, Toast.LENGTH_LONG).show()
                        if (outcome.ok) onDone()
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }

    saveProgress?.let { progress ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Saving…") },
            text = {
                Column {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("$progress%")
                }
            },
            confirmButton = {},
        )
    }
}

/** The waveform with the selection shaded, two draggable handles and the playhead. */
@Composable
private fun WaveformEditor(
    waveform: Waveform,
    mode: TrimMode,
    startMs: Long,
    endMs: Long,
    playheadMs: Long,
    active: Handle,
    onDragHandle: (Handle, Long) -> Unit,
    onTap: (Long) -> Unit,
) {
    val durationMs = waveform.durationMs.coerceAtLeast(1L)
    val waveColor = MaterialTheme.colorScheme.primary
    val dimColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)
    val cutShade = MaterialTheme.colorScheme.error.copy(alpha = 0.18f)
    val keepShade = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
    val handleColor = MaterialTheme.colorScheme.onSurface
    val activeColor = MaterialTheme.colorScheme.tertiary
    val playheadColor = MaterialTheme.colorScheme.error
    val grabPx = with(LocalDensity.current) { 32.dp.toPx() }

    val latestStart by rememberUpdatedState(startMs)
    val latestEnd by rememberUpdatedState(endMs)
    var dragging by remember { mutableStateOf<Handle?>(null) }
    var widthPx by remember { mutableIntStateOf(1) }

    fun xToMs(x: Float): Long = ((x / widthPx.coerceAtLeast(1)) * durationMs).toLong().coerceIn(0L, durationMs)

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
            .pointerInput(durationMs) {
                detectTapGestures { offset -> onTap(xToMs(offset.x)) }
            }
            .pointerInput(durationMs) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val startX = latestStart.toFloat() / durationMs * size.width
                        val endX = latestEnd.toFloat() / durationMs * size.width
                        val dStart = abs(offset.x - startX)
                        val dEnd = abs(offset.x - endX)
                        // Grab whichever handle is nearer; with both far away, still the nearer.
                        dragging = if (dStart <= dEnd || (dStart < grabPx && dEnd >= grabPx)) Handle.START else Handle.END
                        onDragHandle(dragging!!, xToMs(offset.x))
                    },
                    onDragEnd = { dragging = null },
                    onDragCancel = { dragging = null },
                    onDrag = { change, _ ->
                        change.consume()
                        dragging?.let { onDragHandle(it, xToMs(change.position.x)) }
                    },
                )
            },
    ) {
        widthPx = size.width.toInt()
        val w = size.width
        val h = size.height
        val startX = startMs.toFloat() / durationMs * w
        val endX = endMs.toFloat() / durationMs * w

        // Selection shading.
        drawRect(
            color = if (mode == TrimMode.CUT) cutShade else keepShade,
            topLeft = Offset(startX, 0f),
            size = Size((endX - startX).coerceAtLeast(1f), h),
        )

        // Bars: the parts that survive in colour, the parts being removed greyed out.
        val peaks = waveform.peaks
        val barWidth = w / peaks.size
        peaks.forEachIndexed { i, peak ->
            val x = i * barWidth
            val inside = x + barWidth / 2 in startX..endX
            val kept = if (mode == TrimMode.KEEP) inside else !inside
            val barH = (peak.coerceAtLeast(0.02f)) * h * 0.9f
            drawRect(
                color = if (kept) waveColor else dimColor,
                topLeft = Offset(x, (h - barH) / 2),
                size = Size((barWidth * 0.7f).coerceAtLeast(1f), barH),
            )
        }

        // Handles: a line plus a grab tab at the top.
        listOf(Handle.START to startX, Handle.END to endX).forEach { (handle, x) ->
            val color = if (handle == active) activeColor else handleColor
            drawRect(color = color, topLeft = Offset(x - 1.5f, 0f), size = Size(3f, h))
            drawRoundRect(
                color = color,
                topLeft = Offset(x - 10f, 0f),
                size = Size(20f, 26f),
                cornerRadius = CornerRadius(6f, 6f),
            )
        }

        // Playhead.
        val px = playheadMs.toFloat() / durationMs * w
        drawRect(color = playheadColor, topLeft = Offset(px - 1f, 0f), size = Size(2f, h))
    }
}

/** m:ss.t — tenths matter when you're lining up a cut. */
private fun formatPrecise(ms: Long): String {
    val clamped = ms.coerceAtLeast(0L)
    val totalSeconds = clamped / 1000
    val tenths = (clamped % 1000) / 100
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%d:%02d.%d", minutes, seconds, tenths)
}
