package com.andres.musicplayer.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * A slim seek bar: a thin line with a small circular thumb. Tap or drag to seek.
 * [fraction] is 0..1; [onScrub] reports the dragged fraction live and
 * [onScrubFinished] fires when the gesture ends.
 */
@Composable
fun SeekBar(
    fraction: Float,
    onScrub: (Float) -> Unit,
    onScrubFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeColor = MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
    // The gesture detectors below are installed once and outlive recompositions, so they
    // must read the latest callbacks: the caller's lambdas capture the track's duration,
    // and a stale one seeks the next song using the previous song's length.
    val scrub by rememberUpdatedState(onScrub)
    val scrubFinished by rememberUpdatedState(onScrubFinished)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(24.dp)
            .pointerInput(Unit) {
                val r = 7.dp.toPx()
                detectTapGestures { offset ->
                    scrub(((offset.x - r) / (size.width - 2 * r)).coerceIn(0f, 1f))
                    scrubFinished()
                }
            }
            .pointerInput(Unit) {
                val r = 7.dp.toPx()
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        scrub(((offset.x - r) / (size.width - 2 * r)).coerceIn(0f, 1f))
                    },
                    onHorizontalDrag = { change, _ ->
                        scrub(((change.position.x - r) / (size.width - 2 * r)).coerceIn(0f, 1f))
                    },
                    onDragEnd = { scrubFinished() },
                    onDragCancel = { scrubFinished() },
                )
            },
    ) {
        val cy = size.height / 2
        val lineH = 3.dp.toPx()
        val r = 7.dp.toPx()
        val thumbX = r + (size.width - 2 * r) * fraction.coerceIn(0f, 1f)
        drawLine(inactiveColor, Offset(r, cy), Offset(size.width - r, cy), lineH, StrokeCap.Round)
        drawLine(activeColor, Offset(r, cy), Offset(thumbX, cy), lineH, StrokeCap.Round)
        drawCircle(activeColor, radius = r, center = Offset(thumbX, cy))
    }
}
