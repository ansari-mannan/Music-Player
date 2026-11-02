package com.andres.musicplayer.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path

/**
 * Smooth, scalable media-control icons drawn directly on a Canvas — gives a
 * sleeker look than text glyphs and needs no icon dependency. Each fills the
 * size of the [Modifier] it's given and inherits the current content color.
 */

@Composable
fun PlayIcon(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.30f, h * 0.22f)
            lineTo(w * 0.30f, h * 0.78f)
            lineTo(w * 0.80f, h * 0.50f)
            close()
        }
        drawPath(path, tint)
    }
}

@Composable
fun PauseIcon(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val barW = w * 0.16f
        val top = h * 0.24f
        val barH = h * 0.52f
        val r = CornerRadius(barW * 0.5f)
        drawRoundRect(tint, Offset(w * 0.32f - barW / 2, top), Size(barW, barH), r)
        drawRoundRect(tint, Offset(w * 0.68f - barW / 2, top), Size(barW, barH), r)
    }
}

@Composable
fun SkipNextIcon(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val tri = Path().apply {
            moveTo(w * 0.20f, h * 0.26f)
            lineTo(w * 0.20f, h * 0.74f)
            lineTo(w * 0.60f, h * 0.50f)
            close()
        }
        drawPath(tri, tint)
        val barW = w * 0.12f
        drawRoundRect(
            tint,
            Offset(w * 0.66f, h * 0.24f),
            Size(barW, h * 0.52f),
            CornerRadius(barW * 0.5f),
        )
    }
}

@Composable
fun SkipPreviousIcon(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val barW = w * 0.12f
        drawRoundRect(
            tint,
            Offset(w * 0.22f, h * 0.24f),
            Size(barW, h * 0.52f),
            CornerRadius(barW * 0.5f),
        )
        val tri = Path().apply {
            moveTo(w * 0.80f, h * 0.26f)
            lineTo(w * 0.80f, h * 0.74f)
            lineTo(w * 0.40f, h * 0.50f)
            close()
        }
        drawPath(tri, tint)
    }
}

@Composable
fun PlusIcon(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val thick = w * 0.14f
        val r = CornerRadius(thick * 0.5f)
        drawRoundRect(tint, Offset(w * 0.20f, h * 0.5f - thick / 2), Size(w * 0.60f, thick), r)
        drawRoundRect(tint, Offset(w * 0.5f - thick / 2, h * 0.20f), Size(thick, h * 0.60f), r)
    }
}
