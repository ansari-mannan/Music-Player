package com.andres.musicplayer.ui.playlists

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.andres.musicplayer.ui.art.SongArtwork

/**
 * Playlist cover: a 2x2 grid of up to four song thumbnails (VLC-style). Falls
 * back to a single thumbnail / placeholder for 0–1 songs.
 */
@Composable
fun PlaylistCollage(uris: List<String?>, size: Dp, modifier: Modifier = Modifier) {
    if (uris.size <= 1) {
        SongArtwork(uri = uris.firstOrNull(), size = size, modifier = modifier)
        return
    }
    val cells = (uris.take(4) + List(4) { null }).take(4)
    val half = size / 2
    val shape = RoundedCornerShape(if (size.value > 96) 16.dp else 8.dp)
    Column(modifier = modifier.size(size).clip(shape)) {
        Row {
            SongArtwork(uri = cells[0], size = half)
            SongArtwork(uri = cells[1], size = half)
        }
        Row {
            SongArtwork(uri = cells[2], size = half)
            SongArtwork(uri = cells[3], size = half)
        }
    }
}
