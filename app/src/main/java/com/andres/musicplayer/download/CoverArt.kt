package com.andres.musicplayer.download

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Fetches a video's thumbnail and turns it into square JPEG bytes ready to go into an
 * MP4 `covr` atom.
 *
 * YouTube thumbnails are 16:9. Embedding one as-is leaves every player that assumes
 * square art either letterboxing it or stretching it, so it is centre-cropped to a
 * square first — the same shape the app's own manual "change album artwork" path
 * produces. JPEG at quality 90 for the same reason: it is what
 * [com.andres.musicplayer.data.SongRepository]'s artwork path already writes, so a
 * downloaded track and a hand-edited one carry the same kind of image.
 *
 * Every failure here returns null rather than throwing. Cover art is a nicety; a track
 * that downloaded perfectly must not be marked failed because a JPEG didn't arrive.
 */
internal class CoverArtFetcher(private val http: OkHttpClient) {

    /**
     * Walks [urls] in the order given — best first — and returns the first one that
     * downloads, decodes and crops. Null only once every candidate has failed.
     *
     * A single URL was a single point of failure: `maxresdefault` simply does not exist
     * for many videos, and one 404 left the track with no artwork at all even though a
     * smaller image on the same video was sitting right there.
     */
    suspend fun squareJpeg(urls: List<String>): ByteArray? = withContext(Dispatchers.IO) {
        val candidates = urls.filter { it.isNotBlank() }
        if (candidates.isEmpty()) return@withContext null

        candidates.forEachIndexed { index, url ->
            val art = try {
                download(url)?.let { crop(it) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't prepare cover art from $url", e)
                null
            }
            if (art != null) {
                Log.i(
                    TAG,
                    "Cover art from candidate ${index + 1} of ${candidates.size} " +
                        "(${art.size} bytes): $url",
                )
                return@withContext art
            }
        }
        Log.w(TAG, "All ${candidates.size} thumbnail candidates failed")
        null
    }

    private fun download(url: String): ByteArray? {
        val request = Request.Builder().url(url).build()
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "Thumbnail request failed (HTTP ${response.code}) for $url")
                return@use null
            }
            val body = response.body ?: return@use null
            // The declared length is only a hint worth acting on early — a chunked or
            // header-less response reports -1, which is never over any cap. The read
            // below is what actually enforces the limit.
            if (body.contentLength() > MAX_DOWNLOAD_BYTES) {
                Log.w(TAG, "Thumbnail too large (${body.contentLength()} bytes) for $url")
                return@use null
            }
            readBounded(body.byteStream(), url)
        }
    }

    private fun readBounded(input: InputStream, url: String): ByteArray? =
        readAtMost(input, MAX_DOWNLOAD_BYTES).also {
            if (it == null) Log.w(TAG, "Thumbnail exceeded $MAX_DOWNLOAD_BYTES bytes for $url")
        }

    private fun crop(raw: ByteArray): ByteArray? {
        val source = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return null
        val side = squareSide(source.width, source.height)
        if (side <= 0) return null

        val square = if (side == source.width && side == source.height) {
            source
        } else {
            Bitmap.createBitmap(
                source,
                cropOrigin(source.width, side),
                cropOrigin(source.height, side),
                side,
                side,
            )
        }
        return ByteArrayOutputStream().use { out ->
            square.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            if (square !== source) square.recycle()
            source.recycle()
            out.toByteArray()
        }
    }

    private companion object {
        const val TAG = "CoverArt"
        const val JPEG_QUALITY = 90
        const val MAX_DOWNLOAD_BYTES = 8L * 1024 * 1024
    }
}

/**
 * Reads [input] fully, or gives up and returns null the moment it passes [limit] bytes.
 * Also null for an empty stream, which is not an image either way.
 *
 * The size guard has to live here rather than on `Content-Length`. A chunked response, or
 * one from a server that simply omits the header, reports a length of -1 — which is never
 * greater than any cap, so the header check alone waved through exactly the unbounded
 * responses it was written to stop, straight into memory on a phone.
 */
internal fun readAtMost(input: InputStream, limit: Long): ByteArray? {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    input.use {
        while (true) {
            val read = it.read(buffer)
            if (read == -1) break
            if (out.size() + read > limit) return null
            out.write(buffer, 0, read)
        }
    }
    return out.toByteArray().takeIf { bytes -> bytes.isNotEmpty() }
}

/** Side length of the largest square that fits inside a [width] × [height] image. */
internal fun squareSide(width: Int, height: Int): Int =
    if (width <= 0 || height <= 0) 0 else minOf(width, height)

/**
 * Where the square starts along one axis of length [length]. Integer division biases the
 * odd-pixel case towards the top/left by half a pixel, which is invisible and keeps the
 * crop inside the source — rounding up would run one pixel past the edge.
 */
internal fun cropOrigin(length: Int, side: Int): Int = ((length - side) / 2).coerceAtLeast(0)
