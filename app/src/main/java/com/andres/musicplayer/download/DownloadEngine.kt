package com.andres.musicplayer.download

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.andres.musicplayer.data.local.AppDatabase
import com.andres.musicplayer.data.local.dao.DownloadDao
import com.andres.musicplayer.data.local.dao.DownloadedVideoDao
import com.andres.musicplayer.data.local.entity.DownloadEntry
import com.andres.musicplayer.data.local.entity.DownloadEntryWithSource
import com.andres.musicplayer.data.local.entity.DownloadState
import com.andres.musicplayer.data.local.entity.DownloadedVideo
import com.andres.musicplayer.data.local.entity.Song
import com.andres.musicplayer.data.prefs.DownloadPreferences
import com.andres.musicplayer.data.prefs.FolderPreferences
import com.andres.musicplayer.data.tags.Mp4Tag
import com.andres.musicplayer.data.youtube.MetadataResolver
import com.andres.musicplayer.data.youtube.MusicMetadataResolver
import com.andres.musicplayer.data.youtube.ResolutionFailure
import com.andres.musicplayer.data.youtube.ResolvedStream
import com.andres.musicplayer.data.youtube.StreamResolutionException
import com.andres.musicplayer.data.youtube.StreamResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.coroutineContext

/** Live byte counts for one running download, for a progress bar that moves smoothly. */
data class DownloadProgress(val bytesDownloaded: Long, val totalBytes: Long?)

/** A video the sync pass wants downloaded, before it becomes a queue row. */
data class PendingDownload(
    val videoId: String,
    val title: String,
    val thumbnailUrl: String = "",
    val sourcePlaylistId: String? = null,
)

/**
 * Runs the actual downloads: a bounded pool of coroutines, each streaming one audio
 * track to disk with HTTP Range support so it can be paused and resumed from a byte
 * offset instead of restarting.
 *
 * This is a plain object rather than a `WorkManager` worker on purpose. WorkManager
 * units of work are restartable, not pausable — there is no way to suspend one
 * mid-transfer and pick it up at byte 4,812,033 later. [DownloadService] exists only to
 * keep the process alive and show the notification while this engine has work.
 *
 * Bytes land in a private `.part` file first and are moved into the watched folder only
 * once complete, so the library scanner never sees a half-written track.
 */
class DownloadEngine(
    private val appContext: Context,
    private val downloadDao: DownloadDao,
    private val downloadedVideoDao: DownloadedVideoDao,
    private val resolver: StreamResolver,
    /**
     * Live pool size. A flow rather than a [DownloadPreferences] handle so the engine
     * reads whatever the user last chose without being restarted — and so the pool logic
     * can be exercised off-device, which needing a `Context` would rule out.
     */
    private val concurrency: StateFlow<Int>,
    /** Where finished files go. Read per download, not captured, so a re-pick applies. */
    private val watchedFolder: () -> String?,
    /** Bonus metadata lookup; see [MetadataResolver] for why it's replaceable. */
    private val metadataResolver: MetadataResolver = MusicMetadataResolver(),
    /** Injected so the pool can be tested without a live foreground service. */
    private val startService: () -> Unit = { DownloadService.ensureRunning(appContext) },
    /** Puts a finished download into the song library. */
    private val addToLibrary: suspend (Song) -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        // Per-read, not per-transfer: a big track may legitimately take many minutes.
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val coverArt = CoverArtFetcher(http)

    private val jobs = ConcurrentHashMap<String, Job>()
    private val stopReasons = ConcurrentHashMap<String, StopReason>()

    /**
     * Ids auto-retried once already (4.2). In memory only: a process restart grants one
     * more automatic attempt, which is the forgiving direction to be wrong in.
     */
    private val autoRetried = ConcurrentHashMap.newKeySet<String>()
    private val awaitingNetwork = ConcurrentHashMap.newKeySet<String>()

    private val pumpMutex = Mutex()

    private val _progress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val progress: StateFlow<Map<String, DownloadProgress>> = _progress.asStateFlow()

    /** Everything not finished — what the notification counts and the Active tab shows. */
    val activeEntries: Flow<List<DownloadEntryWithSource>> =
        downloadDao.entriesInStates(ACTIVE_STATES)

    val failedEntries: Flow<List<DownloadEntryWithSource>> =
        downloadDao.entriesInState(DownloadState.FAILED)

    val completedEntries: Flow<List<DownloadEntryWithSource>> =
        downloadDao.entriesInState(DownloadState.COMPLETED)

    private var networkCallbackRegistered = false

    private enum class StopReason { PAUSE, CANCEL }

    init {
        // Resizing the pool takes effect for newly-started work; downloads already over
        // a freshly-lowered limit are left to finish rather than being force-paused.
        scope.launch { concurrency.collect { pump() } }
    }

    // --- Public API ---------------------------------------------------------------

    /**
     * Adds work to the queue. A video already queued or already downloaded is ignored,
     * so this is safe to call repeatedly with the same id.
     */
    suspend fun enqueue(
        videoId: String,
        title: String,
        thumbnailUrl: String = "",
        sourcePlaylistId: String? = null,
    ): Boolean {
        if (downloadedVideoDao.getById(videoId) != null) return false
        if (downloadDao.getById(videoId) != null) return false
        downloadDao.upsert(
            DownloadEntry(
                id = videoId,
                videoId = videoId,
                title = title,
                thumbnailUrl = thumbnailUrl,
                sourcePlaylistId = sourcePlaylistId,
                state = DownloadState.QUEUED,
            ),
        )
        startService()
        pump()
        return true
    }

    /**
     * Queues a batch, starting the service and pumping the pool **once** rather than
     * per item. A sync pass finding fifty new songs shouldn't send fifty
     * start-service intents. Returns how many were actually added.
     */
    suspend fun enqueueAll(candidates: List<PendingDownload>): Int {
        if (candidates.isEmpty()) return 0
        val alreadyHave = HashSet<String>()
        alreadyHave += downloadedVideoDao.allVideoIds()
        alreadyHave += downloadDao.allVideoIds()

        val fresh = candidates
            .filter { alreadyHave.add(it.videoId) }
            .map {
                DownloadEntry(
                    id = it.videoId,
                    videoId = it.videoId,
                    title = it.title,
                    thumbnailUrl = it.thumbnailUrl,
                    sourcePlaylistId = it.sourcePlaylistId,
                    state = DownloadState.QUEUED,
                )
            }
        if (fresh.isEmpty()) return 0

        downloadDao.upsertAll(fresh)
        startService()
        pump()
        return fresh.size
    }

    /**
     * The row is marked paused up front rather than only from the cancelled job's
     * cleanup, because a job cancelled before its body starts running never reaches that
     * cleanup — and the item would quietly stay queued and start again on the next pump.
     */
    fun pause(id: String) = scope.launch {
        val job = jobs[id]
        if (job != null) stopReasons[id] = StopReason.PAUSE
        downloadDao.updateState(id, DownloadState.PAUSED)
        job?.cancel()
    }

    fun resume(id: String) = scope.launch {
        awaitingNetwork.remove(id)
        downloadDao.updateState(id, DownloadState.QUEUED)
        startService()
        pump()
    }

    /**
     * Removes the item entirely and discards its partial bytes. Waits for a running job
     * to actually stop before deleting, so the transfer can't recreate the `.part` file
     * a moment after it was removed.
     */
    fun cancel(id: String) = scope.launch {
        val job = jobs[id]
        if (job != null) {
            stopReasons[id] = StopReason.CANCEL
            job.cancel()
            job.join()
        }
        stopReasons.remove(id)
        downloadDao.deleteById(id)
        partFile(id).delete()
        clearProgress(id)
        pump()
    }

    /**
     * Forces everything unfinished to paused, whatever each item's own state was.
     *
     * `DOWNLOADING` is in the transition list as well as being cancelled below, and both
     * are needed. Cancelling a live job is what actually stops the bytes, and its cleanup
     * writes `PAUSED` — but a row can be `DOWNLOADING` with no job behind it at all, after
     * a process death before [recoverInterrupted] has run, and that one would otherwise
     * sit there claiming to be downloading forever.
     *
     * Deliberately not a toggle. Pause All means paused, so an item the user paused by
     * hand ten minutes ago stays exactly where they put it.
     */
    fun pauseAll() = scope.launch {
        downloadDao.transitionAll(PAUSABLE_STATES, DownloadState.PAUSED)
        jobs.keys.toList().forEach { id ->
            stopReasons[id] = StopReason.PAUSE
            jobs[id]?.cancel()
        }
    }

    /**
     * The mirror image: everything paused goes back to the queue, and the pool starts as
     * many as the limit allows. Items already queued or running are left alone rather
     * than being flipped, which is what a toggle would do to them.
     */
    fun resumeAll() = scope.launch {
        awaitingNetwork.clear()
        downloadDao.transitionAll(listOf(DownloadState.PAUSED), DownloadState.QUEUED)
        startService()
        pump()
    }

    fun retry(id: String) = scope.launch {
        autoRetried.remove(id)
        downloadDao.requeue(id)
        startService()
        pump()
    }

    fun retryAllFailed() = scope.launch {
        autoRetried.clear()
        downloadDao.transitionAll(listOf(DownloadState.FAILED), DownloadState.QUEUED)
        startService()
        pump()
    }

    /** Drops a finished item from the history. Never touches the downloaded file. */
    fun removeFromHistory(id: String) = scope.launch { downloadDao.deleteById(id) }

    /**
     * Re-queues anything left DOWNLOADING by a process death. Called when the service
     * starts, so a crash mid-transfer resumes from its saved offset rather than sticking.
     */
    suspend fun recoverInterrupted() {
        downloadDao.transitionAll(listOf(DownloadState.DOWNLOADING), DownloadState.QUEUED)
        pump()
    }

    // --- Pool ----------------------------------------------------------------------

    /** Starts as much queued work as the concurrency limit allows. */
    suspend fun pump() {
        pumpMutex.withLock {
            val limit = concurrency.value
            val free = limit - jobs.size
            if (free <= 0) return@withLock
            downloadDao.nextInState(DownloadState.QUEUED, free).forEach { entry ->
                if (jobs.containsKey(entry.id)) return@forEach
                val job = scope.launch { runDownload(entry.id) }
                jobs[entry.id] = job
                job.invokeOnCompletion {
                    jobs.remove(entry.id)
                    scope.launch { pump() }
                }
            }
        }
    }

    private suspend fun runDownload(id: String) {
        val entry = downloadDao.getById(id) ?: return
        var written = entry.bytesDownloaded
        var total = entry.totalBytes
        var metadata: Deferred<TrackMetadata?>? = null
        try {
            downloadDao.updateState(id, DownloadState.DOWNLOADING)

            // Resolved URLs expire within minutes, so this happens per attempt — never
            // cached, and never reused across a pause/resume cycle.
            val stream = resolver.resolve(entry.videoId)
            // A hand-started download only knows the video id until this point.
            if (entry.title != stream.title) downloadDao.updateTitle(id, stream.title)

            // Tags and cover art come from other servers and don't depend on the audio,
            // so they are fetched alongside it rather than after it. A child of this job:
            // a pause or cancel stops it too.
            metadata = CoroutineScope(coroutineContext).async { prepareMetadata(id, entry, stream) }

            val part = partFile(id)
            part.parentFile?.mkdirs()

            // Trust the smaller of what the DB claims and what is really on disk; the
            // two can diverge if the process died between a write and its persist. The
            // file is cut back to that point, because appending to a longer file would
            // splice the same bytes in twice.
            // A different size from last time means YouTube is now serving a different
            // file (another format or encode), and resuming would splice two together.
            val sameFile = entry.totalBytes == null || stream.contentLength == null ||
                entry.totalBytes == stream.contentLength
            if (!sameFile) total = null
            val offset = if (sameFile) {
                minOf(part.length(), entry.bytesDownloaded).coerceAtLeast(0L)
            } else {
                0L
            }
            if (part.exists() && part.length() != offset) {
                java.io.RandomAccessFile(part, "rw").use { it.setLength(offset) }
            }
            written = offset
            total = total ?: stream.contentLength

            java.io.FileOutputStream(part, true).use { out ->
                val buffer = ByteArray(BUFFER_BYTES)
                var lastPersist = System.currentTimeMillis()
                var lastEmit = 0L

                // Bounded ranges rather than one open-ended request: YouTube throttles a
                // single long-lived stream to roughly playback speed, but serves each
                // bounded chunk at full speed. Connections are pooled, so the extra
                // requests cost a few headers each.
                while (total == null || written < total!!) {
                    val chunkEnd = (written + CHUNK_BYTES - 1)
                        .let { end -> total?.let { minOf(end, it - 1) } ?: end }
                    val request = Request.Builder()
                        .url(stream.url)
                        .header("Range", "bytes=$written-$chunkEnd")
                        .build()

                    val chunkStart = written
                    var wholeFile = false
                    http.newCall(request).execute().use { response ->
                        when {
                            response.code == 206 -> Unit
                            response.isSuccessful -> {
                                // Range ignored: this response is the whole file, so the
                                // partial is thrown away and replaced by it.
                                wholeFile = true
                                out.channel.truncate(0)
                                written = 0L
                            }
                            // Past the end with a known total means there is nothing left
                            // to fetch; otherwise the partial is bad, not the video.
                            response.code == 416 && total != null && written >= total!! -> return@use
                            response.code == 416 -> throw RestartFromScratch()
                            else -> throw IOException("Download failed (HTTP ${response.code}).")
                        }

                        val body = response.body ?: throw IOException("Empty response from YouTube.")
                        if (wholeFile) {
                            total = body.contentLength().takeIf { it > 0 } ?: total
                        } else {
                            totalFromContentRange(response.header("Content-Range"))?.let { total = it }
                        }

                        body.byteStream().use { input ->
                            while (true) {
                                // Without this the loop never notices a pause or cancel:
                                // nothing in it suspends, so the bytes kept flowing.
                                coroutineContext.ensureActive()
                                val read = input.read(buffer)
                                if (read == -1) break
                                out.write(buffer, 0, read)
                                written += read

                                val now = System.currentTimeMillis()
                                if (now - lastEmit >= EMIT_INTERVAL_MS) {
                                    lastEmit = now
                                    setProgress(id, written, total)
                                }
                                if (now - lastPersist >= PERSIST_INTERVAL_MS) {
                                    lastPersist = now
                                    out.flush()
                                    withContext(NonCancellable) {
                                        downloadDao.updateProgress(id, written, total)
                                    }
                                }
                            }
                        }
                    }
                    if (wholeFile) break
                    // A short or empty chunk with no known total is the end of the stream.
                    if (written == chunkStart) break
                    if (total == null && written - chunkStart < CHUNK_BYTES) break
                }
                out.fd.sync()
            }
            if (total != null && written < total!!) {
                throw IOException("Download ended early ($written of $total bytes).")
            }

            finish(id, entry, part, stream, written, total, metadata.await())
        } catch (cancellation: CancellationException) {
            withContext(NonCancellable) { handleStop(id, written, total) }
            throw cancellation
        } catch (restart: RestartFromScratch) {
            partFile(id).delete()
            downloadDao.updateProgress(id, 0L, null)
            downloadDao.updateState(id, DownloadState.QUEUED)
        } catch (e: Exception) {
            withContext(NonCancellable) { handleFailure(id, written, total, e) }
        } finally {
            // Nothing to wait for once the download itself has failed or stopped.
            metadata?.cancel()
        }
    }

    /**
     * Moves the finished `.part` into the watched folder and records the video as
     * downloaded. The title and container come from the stream that was actually fetched
     * — the resolver is deliberately not called a second time here, both to avoid a
     * pointless round trip and because a failure at this point would discard a complete
     * download.
     */
    private suspend fun finish(
        id: String,
        entry: DownloadEntry,
        part: File,
        stream: ResolvedStream,
        written: Long,
        total: Long?,
        metadata: TrackMetadata?,
    ) {
        val folder = watchedFolder()
            ?: throw IOException("No music folder chosen — pick one in Settings first.")
        val extension = stream.fileExtension
        val title = stream.title

        val fileName = "${sanitiseFileName(title.ifBlank { entry.videoId })}.$extension"
        val (destination, hasArt) =
            writeIntoWatchedFolder(Uri.parse(folder), fileName, part, extension, entry.videoId, metadata)

        val durationMs = durationOf(part)
        part.delete()
        downloadedVideoDao.insert(
            DownloadedVideo(
                videoId = entry.videoId,
                sourcePlaylistId = entry.sourcePlaylistId,
                filePath = destination,
            ),
        )
        downloadDao.updateProgress(id, written, total ?: written)
        // Recorded from what was read back out of the file, so the Completed list can
        // show artwork only where artwork really exists.
        downloadDao.setHasEmbeddedArt(id, hasArt)
        downloadDao.markCompleted(id)
        clearProgress(id)

        // Straight into the library, so a finished download is playable now rather than
        // after the next launch's scan. That scan then sees the uri as known and skips it.
        try {
            addToLibrary(
                Song(
                    uri = destination,
                    title = metadata?.tags?.title?.takeIf { it.isNotBlank() } ?: title.ifBlank { entry.videoId },
                    artist = metadata?.tags?.artist,
                    album = metadata?.tags?.album,
                    durationMs = durationMs,
                ),
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't add ${entry.videoId} to the library; the next scan will", e)
        }
    }

    /** Track length read from the local copy, which is quicker than going through SAF. */
    private fun durationOf(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            retriever.release()
        }
    }

    /** Tags to write into a finished download. */
    private class TrackMetadata(val tags: TrackTags, val cover: ByteArray?)

    /**
     * Title, artist, album and a square `covr` thumbnail for the downloaded file, so a
     * fresh track arrives in the library already described rather than as a filename.
     *
     * Deliberately best-effort: a track that transferred perfectly must never be marked
     * failed because YouTube's image server had a bad minute, so everything short of a
     * cancellation is logged and swallowed. Only MP4-family containers get any — the tag
     * writer understands nothing else, and would simply refuse a webm.
     */
    private suspend fun prepareMetadata(
        id: String,
        entry: DownloadEntry,
        stream: ResolvedStream,
    ): TrackMetadata? {
        val videoId = entry.videoId
        if (stream.fileExtension.lowercase() !in Mp4Tag.EXTENSIONS) {
            // Never silent. This branch hid a whole release worth of missing cover art:
            // an untaggable container looks exactly like a clean download from outside.
            Log.w(TAG, "No tag writer for .${stream.fileExtension} — $videoId stays untagged")
            return null
        }
        return try {
            val tags = deriveTags(
                videoTitle = stream.title,
                uploader = stream.uploader,
                playlistTitle = downloadDao.getByIdWithSource(id)?.playlistTitle,
                music = metadataResolver.resolve(videoId),
            )
            val cover = coverJpeg(entry, stream)
            if (cover == null) Log.w(TAG, "No cover art to embed for $videoId")
            TrackMetadata(tags, cover)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't gather metadata for $videoId", e)
            null
        }
    }

    /**
     * Cover art for the finished file, tried best-first.
     *
     * Several candidates rather than one, because a single URL is a single point of
     * failure: `maxresdefault` does not exist for every video and 404s when it doesn't,
     * and a track would end up with no artwork at all even though the next size down
     * would have worked. The extractor's ranked list leads; the playlist listing's own
     * image is appended as a last resort, since it is all a sync-queued item had before
     * the stream was resolved.
     */
    private suspend fun coverJpeg(entry: DownloadEntry, stream: ResolvedStream): ByteArray? {
        val candidates = (stream.thumbnailUrls + entry.thumbnailUrl)
            .filter { it.isNotBlank() }
            .distinct()
        return coverArt.squareJpeg(candidates)
    }

    /** A pause keeps the partial file; a cancel throws it away. */
    private suspend fun handleStop(id: String, written: Long, total: Long?) {
        when (stopReasons.remove(id)) {
            StopReason.CANCEL -> {
                downloadDao.deleteById(id)
                partFile(id).delete()
                clearProgress(id)
            }
            // No recorded reason means the scope itself was torn down; treat it as a
            // pause so the bytes already fetched are not thrown away.
            StopReason.PAUSE, null -> {
                downloadDao.updateProgress(id, written, total)
                downloadDao.updateState(id, DownloadState.PAUSED)
            }
        }
    }

    private suspend fun handleFailure(id: String, written: Long, total: Long?, e: Exception) {
        // The extractor's own wording is worth having in the log even though the user is
        // shown the plain-language version — it is what identifies which of YouTube's
        // internals moved, when downloads start failing across the board.
        val detail = (e as? StreamResolutionException)?.detail
        Log.w(TAG, "Download $id failed" + (detail?.let { ": $it" } ?: ""), e)
        downloadDao.updateProgress(id, written, total)
        downloadDao.markFailed(id, describe(e))
        clearProgress(id)

        // One automatic retry, and only for something that looks like a blip. Anything
        // else (or a second failure) waits for the user to press Retry.
        if (isTransient(e) && autoRetried.add(id)) {
            awaitingNetwork.add(id)
            registerNetworkCallback()
        }
    }

    // --- Failure classification -----------------------------------------------------

    /**
     * [StreamResolutionException] is matched before the plain [IOException] branches it
     * inherits from, so a resolution failure is judged on why it failed rather than on
     * being an IOException. Only the one that never reached YouTube is worth retrying
     * automatically: an out-of-date extractor will fail again immediately, and a captcha
     * needs time rather than another request.
     */
    private fun isTransient(e: Exception): Boolean = when (e) {
        is StreamResolutionException -> e.failure == ResolutionFailure.NETWORK
        is UnknownHostException, is SocketTimeoutException, is SocketException,
        is SSLException,
        -> true
        is IOException -> e.message?.contains("HTTP 5") == true
        else -> false
    }

    private fun describe(e: Exception): String = when (e) {
        // Says which kind of "couldn't read this video" it was — most importantly,
        // whether it is the extractor that has fallen behind YouTube.
        is StreamResolutionException -> e.failure.userMessage
        is UnknownHostException -> "No internet connection."
        is SocketTimeoutException -> "The connection timed out."
        is IOException -> e.message ?: "Network error."
        else -> e.message ?: "Unexpected error."
    }

    /**
     * Waits for connectivity to come back, then re-queues the one auto-retry each failed
     * item is allowed. Unregisters itself once nothing is waiting.
     */
    private fun registerNetworkCallback() {
        if (networkCallbackRegistered) return
        val manager = appContext.getSystemService(ConnectivityManager::class.java) ?: return
        networkCallbackRegistered = true
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        manager.registerNetworkCallback(
            request,
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val ids = awaitingNetwork.toList()
                    if (ids.isEmpty()) return
                    awaitingNetwork.clear()
                    scope.launch {
                        ids.forEach { downloadDao.requeue(it) }
                        startService()
                        pump()
                    }
                }
            },
        )
    }

    // --- File handling ---------------------------------------------------------------

    private fun partFile(id: String) = File(File(appContext.filesDir, "downloads"), "$id.part")

    /**
     * Writes the finished download into the SAF-granted watched folder in a single pass,
     * tags applied on the way through, de-duplicating the name rather than overwriting
     * anything already there. The `.part` itself is never modified, so a failure here
     * leaves a complete download to retry from, and the half-written document is removed.
     *
     * Returns the destination uri, and whether a cover is in the written file — checked
     * from the exact `moov` that was written rather than trusted from the image fetch, so
     * the Completed list only shows artwork that really exists.
     */
    private fun writeIntoWatchedFolder(
        treeUri: Uri,
        fileName: String,
        source: File,
        extension: String,
        videoId: String,
        metadata: TrackMetadata?,
    ): Pair<String, Boolean> {
        val tree = DocumentFile.fromTreeUri(appContext, treeUri)
            ?: throw IOException("Music folder is no longer accessible.")
        if (!tree.canWrite()) throw IOException("No permission to write to the music folder.")

        // One listing of the folder's names. `findFile` lists the whole folder through the
        // provider on every call, and this loop could call it several times per download.
        val taken = existingNames(treeUri)
        val base = fileName.substringBeforeLast('.', fileName)
        var candidate = fileName
        var suffix = 1
        while (candidate in taken) {
            candidate = "$base ($suffix).$extension"
            suffix++
        }

        val target = tree.createFile(mimeFor(extension), candidate)
            ?: throw IOException("Couldn't create the file in the music folder.")
        val openOutput = { appContext.contentResolver.openOutputStream(target.uri, "wt") }
        try {
            var opened = false
            val tagged = metadata?.let { m ->
                try {
                    Mp4Tag.copyTagged(
                        source = source,
                        title = m.tags.title,
                        artist = m.tags.artist,
                        album = m.tags.album,
                        artwork = m.cover,
                        artworkMime = "image/jpeg",
                    ) { openOutput().also { opened = true } }
                } catch (e: Exception) {
                    // A failure once bytes are flowing is a failed write, not a tag problem.
                    if (opened) throw e
                    Log.w(TAG, "Couldn't tag the download for $videoId", e)
                    null
                }
            }
            if (tagged == null) {
                if (metadata != null) Log.w(TAG, "Tag writer refused the metadata for $videoId")
                (openOutput() ?: throw IOException("Couldn't write into the music folder.")).use { out ->
                    source.inputStream().use { it.copyTo(out, BUFFER_BYTES) }
                }
            } else if (metadata != null) {
                val via = if (metadata.tags.fromMusicMetadata) "YT-Music metadata" else "heuristics"
                Log.i(
                    TAG,
                    "Tagged $videoId via $via: artist=${metadata.tags.artist ?: "-"}, " +
                        "album=${metadata.tags.album ?: "-"}, " +
                        "cover=${tagged.coverArtSize?.let { "$it bytes" } ?: "none"}",
                )
                if (metadata.cover != null && tagged.coverArtSize == null) {
                    Log.w(TAG, "covr write reported success but no cover is readable in $videoId")
                }
            }
            return target.uri.toString() to (tagged?.coverArtSize != null)
        } catch (e: Exception) {
            runCatching { target.delete() }
            throw e
        }
    }

    /** Every file name directly inside the watched folder. */
    private fun existingNames(treeUri: Uri): Set<String> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        val names = HashSet<String>()
        appContext.contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) cursor.getString(0)?.let { names += it }
        }
        return names
    }

    private fun mimeFor(extension: String) = when (extension.lowercase()) {
        "m4a", "mp4", "aac" -> "audio/mp4"
        "webm", "opus" -> "audio/webm"
        "mp3" -> "audio/mpeg"
        else -> "audio/*"
    }

    // --- Progress bookkeeping ---------------------------------------------------------

    private fun setProgress(id: String, written: Long, total: Long?) {
        _progress.value = _progress.value + (id to DownloadProgress(written, total))
    }

    private fun clearProgress(id: String) {
        _progress.value = _progress.value - id
    }

    /** The whole file's size from a `Content-Range: bytes a-b/total` header, if stated. */
    private fun totalFromContentRange(contentRange: String?): Long? =
        contentRange?.substringAfter('/', "")?.toLongOrNull()?.takeIf { it > 0 }

    /** Thrown to restart a download whose partial file the server rejected. */
    private class RestartFromScratch : Exception()

    companion object {
        private const val TAG = "DownloadEngine"
        private const val BUFFER_BYTES = 64 * 1024

        /** Per-request range; the size yt-dlp uses for YouTube to stay clear of throttling. */
        private const val CHUNK_BYTES = 10L * 1024 * 1024
        private const val EMIT_INTERVAL_MS = 250L
        private const val PERSIST_INTERVAL_MS = 2_000L

        val ACTIVE_STATES = listOf(
            DownloadState.QUEUED,
            DownloadState.DOWNLOADING,
            DownloadState.PAUSED,
        )

        /** What Pause All acts on: everything unfinished that isn't already paused. */
        val PAUSABLE_STATES = listOf(DownloadState.QUEUED, DownloadState.DOWNLOADING)

        fun from(context: Context): DownloadEngine {
            val db = AppDatabase.getInstance(context)
            val folderPrefs = FolderPreferences(context)
            return DownloadEngine(
                appContext = context.applicationContext,
                downloadDao = db.downloadDao(),
                downloadedVideoDao = db.downloadedVideoDao(),
                resolver = StreamResolver(),
                // Process-wide, so the picker in Settings and the one in the Downloads
                // tab both reach this engine rather than each nudging a private copy.
                concurrency = DownloadPreferences(context).concurrency,
                watchedFolder = { folderPrefs.watchedFolderUri },
                addToLibrary = { song -> db.songDao().insert(song) },
            )
        }
    }
}

/**
 * Strips the characters Android filesystems (and the FAT32 formatting most SD cards
 * still use) reject, and keeps the name short enough to survive a 255-byte filename
 * limit once a " (2)" de-duplication suffix and the extension are appended.
 *
 * Hyphens, brackets and apostrophes are kept - YouTube titles are full of them and they
 * are perfectly legal.
 */
internal fun sanitiseFileName(raw: String): String {
    val cleaned = raw
        // Control characters become spaces rather than vanishing, so a tab inside a
        // title still separates words. Done by code point rather than a regex range:
        // typing control-character escapes into a pattern is easy to get wrong, and a
        // stray literal NUL in the source is invisible in review.
        .map { if (it.code < 0x20) ' ' else it }
        .joinToString("")
        .replace(ILLEGAL_FILENAME_CHARS, " ")
        .replace(WHITESPACE_RUN, " ")
        .trim()
        .trimEnd('.', ' ')
    return cleaned.take(120).trimEnd('.', ' ').ifBlank { "download" }
}

/**
 * Both slashes are included: forward is the path separator here, backslash is one on
 * FAT-formatted removable storage, and neither belongs in a track name.
 */
private val ILLEGAL_FILENAME_CHARS = Regex("""[\\/:*?"<>|]""")

private val WHITESPACE_RUN = Regex("""\s+""")
