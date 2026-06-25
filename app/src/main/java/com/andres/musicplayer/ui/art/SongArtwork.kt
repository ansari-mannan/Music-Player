package com.andres.musicplayer.ui.art

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.andres.musicplayer.ui.player.ArtworkPlaceholder

/**
 * Square album art for a song. Loads the embedded cover art (via the Coil
 * [AudioArtFetcher]) on top of the music-note placeholder tile.
 *
 * Uses [AsyncImage] (not SubcomposeAsyncImage) so it does not spin up a
 * subcomposition slot per row — that matters when a [androidx.compose.foundation.lazy.LazyColumn]
 * has hundreds of song rows. The placeholder sits behind the image and simply
 * shows through while loading or when the file has no embedded art.
 */
@Composable
fun SongArtwork(uri: String?, size: Dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(if (size.value > 96) 16.dp else 8.dp)
    if (uri.isNullOrBlank()) {
        ArtworkPlaceholder(size, modifier)
        return
    }
    Box(modifier = modifier.size(size).clip(shape)) {
        ArtworkPlaceholder(size)
        AsyncImage(
            model = AudioArt(Uri.parse(uri)),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size),
        )
    }
}
