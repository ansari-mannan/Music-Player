package com.andres.musicplayer.ui.art

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.disk.DiskCache
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import okio.Buffer
import okio.FileSystem

/**
 * Coil model: "load the embedded cover art of this audio file".
 *
 * [version] is part of the cache key only: bumping [ArtworkVersion.value] (after we
 * embed new art into a file) makes every [AudioArt] key change, so Coil re-reads the
 * file instead of serving the stale cached cover.
 */
data class AudioArt(val uri: Uri, val version: Int = ArtworkVersion.value)

/**
 * Counter that invalidates cached cover art when artwork is edited.
 *
 * Persisted, because the art cache is on disk: a counter that restarted at 0 every
 * launch would bring back covers cached before an edit made in an earlier session.
 */
object ArtworkVersion {
    private const val PREFS = "artwork_cache"
    private const val KEY = "version"
    private var prefs: SharedPreferences? = null

    var value by mutableIntStateOf(0)
        private set

    /** Call once at startup (App.onCreate), before anything loads artwork. */
    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .also { value = it.getInt(KEY, 0) }
    }

    /** Call after embedding new artwork so visible covers reload. */
    fun bump() {
        value++
        prefs?.edit()?.putInt(KEY, value)?.apply()
    }
}

/**
 * Loads the embedded cover art of an audio file for Coil.
 *
 * Extracted art goes into Coil's disk cache, keyed by file and [ArtworkVersion]. Pulling a
 * picture out of an audio file means opening it through SAF and parsing its tags, and
 * that used to happen again for every visible row on every launch. Files with no art get
 * an empty entry, so they aren't re-parsed every time either.
 *
 * The bytes are handed to Coil as a source rather than decoded here, so Coil's own
 * decoder downsamples to the size actually drawn.
 */
class AudioArtFetcher(
    private val data: AudioArt,
    private val context: Context,
    private val options: Options,
    private val diskCache: DiskCache?,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val key = diskCacheKey(data)
        val cache = diskCache

        if (cache != null && options.diskCachePolicy.readEnabled) {
            cache.openSnapshot(key)?.let { snapshot ->
                if (cache.fileSystem.metadata(snapshot.data).size == 0L) {
                    snapshot.close()
                    return null // Known to have no art.
                }
                return SourceFetchResult(
                    source = ImageSource(snapshot.data, cache.fileSystem, key, snapshot),
                    mimeType = null,
                    dataSource = DataSource.DISK,
                )
            }
        }

        val bytes = extract() ?: ByteArray(0)
        if (cache != null && options.diskCachePolicy.writeEnabled) {
            cache.openEditor(key)?.let { editor ->
                try {
                    cache.fileSystem.write(editor.data) { write(bytes) }
                    editor.commit()
                } catch (_: Exception) {
                    editor.abort()
                }
            }
        }
        if (bytes.isEmpty()) return null
        return SourceFetchResult(
            source = ImageSource(Buffer().write(bytes), FileSystem.SYSTEM),
            mimeType = null,
            dataSource = DataSource.DISK,
        )
    }

    private fun extract(): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, data.uri)
            retriever.embeddedPicture
        } catch (_: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    class Factory(private val context: Context) : Fetcher.Factory<AudioArt> {
        override fun create(data: AudioArt, options: Options, imageLoader: ImageLoader): Fetcher =
            AudioArtFetcher(data, context.applicationContext, options, imageLoader.diskCache)
    }

    /**
     * Without a key Coil keeps nothing for a custom model in its memory cache, so every
     * row that scrolled back into view was extracted and decoded from scratch.
     */
    class Keyer : coil3.key.Keyer<AudioArt> {
        override fun key(data: AudioArt, options: Options): String = diskCacheKey(data)
    }
}

private fun diskCacheKey(data: AudioArt): String = "audio-art:${data.version}:${data.uri}"
