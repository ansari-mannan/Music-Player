package com.andres.musicplayer.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.andres.musicplayer.ui.art.SongArtwork
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Persistent bar showing the current track. Tap the row to open Now Playing; swipe the
 * bar sideways to dismiss it — [onDismiss] shuts playback (and the app) down entirely.
 */
@Composable
fun MiniPlayer(
    player: PlayerState,
    onClick: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = player.current ?: return
    val scope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }
    val dismissThresholdPx = with(LocalDensity.current) { 120.dp.toPx() }

    Surface(
        tonalElevation = 3.dp,
        modifier = modifier
            .fillMaxWidth()
            .offset { IntOffset(offsetX.value.roundToInt(), 0) }
            .pointerInput(current.id) {
                val widthPx = size.width.toFloat()
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        scope.launch { offsetX.snapTo(offsetX.value + dragAmount) }
                    },
                    onDragEnd = {
                        if (abs(offsetX.value) > dismissThresholdPx) {
                            // Fling it the rest of the way out, then shut everything down.
                            val target = if (offsetX.value > 0) widthPx else -widthPx
                            scope.launch {
                                offsetX.animateTo(target)
                                onDismiss()
                            }
                        } else {
                            scope.launch { offsetX.animateTo(0f) }
                        }
                    },
                    onDragCancel = { scope.launch { offsetX.animateTo(0f) } },
                )
            },
    ) {
        Column {
            Row(
                modifier = Modifier
                    .clickable(onClick = onClick)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SongArtwork(uri = current.uri, size = 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = current.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = current.artist ?: "Unknown artist",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                FilledIconButton(onClick = { player.togglePlayPause() }) {
                    if (player.showPlayButton) {
                        PlayIcon(Modifier.size(20.dp))
                    } else {
                        PauseIcon(Modifier.size(20.dp))
                    }
                }
                IconButton(onClick = { player.next() }) {
                    SkipNextIcon(Modifier.size(24.dp))
                }
            }
            val duration = player.durationMs
            val progress = if (duration > 0L) {
                (player.positionMs.toFloat() / duration).coerceIn(0f, 1f)
            } else {
                0f
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
