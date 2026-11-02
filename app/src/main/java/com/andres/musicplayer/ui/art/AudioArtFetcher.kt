package com.andres.musicplayer.ui.art

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import coil3.size.Size
import coil3.size.pxOrElse

/**
 * Coil model: "load the embedded cover art of this audio file".
 *
 * [version] is part of the cache key only: bumping [ArtworkVersion.value] (after we
 * embed new art into a file) makes every [AudioArt] key change, so Coil re-reads the
 * file instead of serving the stale cached cover.
 */
data class AudioArt(val uri: Uri, val version: Int = ArtworkVersion.value)

/** Process-wide counter that invalidates cached cover art when artwork is edited. */
object ArtworkVersion {
    var value by mutableIntStateOf(0)
        private set

    /** Call after embedding new artwork so visible covers reload. */
    fun bump() { value++ }
}

/**
 * Extracts the embedded picture (ID3 cover art) from an MP3 via
 * [MediaMetadataRetriever]. Coil caches the result by key, so each file is only
 * read once. Returns null when the file has no art (Coil then shows the fallback).
 */
class AudioArtFetcher(
    private val data: AudioArt,
    private val context: Context,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, data.uri)
            val bytes = retriever.embeddedPicture ?: return null
            // Decode near the size actually drawn: a full 720px+ cover per 44dp list row
            // thrashes Coil's memory cache and costs ~2 MB of heap per visible row.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val sampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, options.size)
            val bitmap = BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sampleSize },
            ) ?: return null
            ImageFetchResult(
                image = bitmap.asImage(),
                isSampled = sampleSize > 1,
                dataSource = DataSource.DISK,
            )
        } catch (_: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    class Factory(private val context: Context) : Fetcher.Factory<AudioArt> {
        override fun create(data: AudioArt, options: Options, imageLoader: ImageLoader): Fetcher =
            AudioArtFetcher(data, context.applicationContext, options)
    }
}

/**
 * Largest power-of-two downsample that keeps the image at least as big as the requested
 * [size] on both axes, so the crop to the tile never upscales. 1 when the size is unknown.
 */
private fun sampleSizeFor(width: Int, height: Int, size: Size): Int {
    val targetWidth = size.width.pxOrElse { 0 }
    val targetHeight = size.height.pxOrElse { 0 }
    if (width <= 0 || height <= 0 || targetWidth <= 0 || targetHeight <= 0) return 1
    var sample = 1
    while (width / (sample * 2) >= targetWidth && height / (sample * 2) >= targetHeight) {
        sample *= 2
    }
    return sample
}
