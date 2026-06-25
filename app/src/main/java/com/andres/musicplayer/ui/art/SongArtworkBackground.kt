package com.andres.musicplayer.ui.art

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/**
 * A heavily blurred, dimmed full-bleed version of a song's cover art, used as a
 * Now Playing backdrop so the art "bleeds" into the surroundings. Renders
 * nothing when there's no uri (or no embedded art). Blur applies on Android 12+;
 * older devices just show the dimmed image.
 */
@Composable
fun SongArtworkBackground(uri: String?, modifier: Modifier = Modifier) {
    if (uri.isNullOrBlank()) return
    AsyncImage(
        model = AudioArt(Uri.parse(uri)),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        alpha = 0.85f,
        modifier = modifier.blur(48.dp),
    )
}
