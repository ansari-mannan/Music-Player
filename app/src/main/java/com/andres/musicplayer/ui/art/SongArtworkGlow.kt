package com.andres.musicplayer.ui.art

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/**
 * A soft, blurred copy of a song's cover art meant to sit directly behind the
 * crisp artwork so colour appears to glow out from behind its center. The
 * unbounded blur lets it bleed past its bounds and fade into the surroundings.
 */
@Composable
fun SongArtworkGlow(uri: String?, modifier: Modifier = Modifier) {
    if (uri.isNullOrBlank()) return
    AsyncImage(
        model = AudioArt(Uri.parse(uri)),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        alpha = 0.55f,
        modifier = modifier.blur(72.dp, BlurredEdgeTreatment.Unbounded),
    )
}
