package com.andres.musicplayer.ui.art

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options

/** Coil model: "load the embedded cover art of this audio file". */
data class AudioArt(val uri: Uri)

/**
 * Extracts the embedded picture (ID3 cover art) from an MP3 via
 * [MediaMetadataRetriever]. Coil caches the result by key, so each file is only
 * read once. Returns null when the file has no art (Coil then shows the fallback).
 */
class AudioArtFetcher(
    private val data: AudioArt,
    private val context: Context,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, data.uri)
            val bytes = retriever.embeddedPicture ?: return null
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            ImageFetchResult(
                image = bitmap.asImage(),
                isSampled = false,
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
            AudioArtFetcher(data, context.applicationContext)
    }
}
