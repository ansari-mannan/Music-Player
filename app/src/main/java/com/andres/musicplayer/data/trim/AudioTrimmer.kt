package com.andres.musicplayer.data.trim

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max

/** What to do with the selection between [startMs] and [endMs]. */
enum class TrimMode { KEEP, CUT }

data class TrimPlan(val mode: TrimMode, val startMs: Long, val endMs: Long)

/** Loudness envelope for drawing: one 0..1 peak per bucket across the whole track. */
class Waveform(val peaks: FloatArray, val durationMs: Long)

/**
 * The audio side of the trim / cut tool: reads a waveform to draw, and exports the
 * trimmed result as a new AAC (.m4a) file.
 */
@OptIn(UnstableApi::class)
object AudioTrimmer {

    private const val TAG = "AudioTrimmer"

    /** Fade at the outer edges in Keep mode: crowd noise eases out rather than stopping dead. */
    private const val EDGE_FADE_MS = 250L

    /** Fade either side of a join in Cut mode: just enough to avoid a click. */
    private const val JOIN_FADE_MS = 40L

    /** Shorter pieces than this are dropped rather than exported as a blip. */
    private const val MIN_SEGMENT_MS = 50L

    /** 256 kbps AAC: re-encoding at well above the source's bitrate keeps the loss inaudible. */
    private const val BITRATE = 256_000

    /** The [start, end) ranges of the original that end up in the output, in order. */
    fun segments(plan: TrimPlan, durationMs: Long): List<LongRange> {
        val start = plan.startMs.coerceIn(0L, durationMs)
        val end = plan.endMs.coerceIn(start, durationMs)
        val raw = when (plan.mode) {
            TrimMode.KEEP -> listOf(start until end)
            TrimMode.CUT -> listOf(0L until start, end until durationMs)
        }
        return raw.filter { it.last - it.first + 1 >= MIN_SEGMENT_MS }
    }

    /** Length of the result, for showing before saving. */
    fun resultDurationMs(plan: TrimPlan, durationMs: Long): Long =
        segments(plan, durationMs).sumOf { it.last - it.first + 1 }

    // --- Waveform ------------------------------------------------------------------

    /** Past this length the waveform is sampled at points instead of decoded end to end. */
    private const val FULL_DECODE_LIMIT_US = 12L * 60 * 1_000_000

    /** Compressed packets decoded per sample point in sampled mode (~0.15 s of AAC). */
    private const val PACKETS_PER_POINT = 6

    /**
     * The track's loudness envelope, for drawing.
     *
     * Fast paths, in order:
     * 1. A cached copy from an earlier visit (keyed by uri + file size, so a trim or any
     *    rewrite invalidates it) comes back instantly.
     * 2. Songs up to [FULL_DECODE_LIMIT_US] are decoded end to end, keeping the decoder
     *    fed and drained without waiting — tens of times faster than real time.
     * 3. Longer recordings (whole concerts) are sampled: a short burst decoded at each of
     *    the [buckets] points, so an hour-long file costs about the same as a song.
     *
     * [onPartial] receives the picture so far a few times a second, so the screen can draw
     * while the rest is still being read.
     */
    suspend fun waveform(
        context: Context,
        uri: Uri,
        buckets: Int = 600,
        onPartial: (Waveform) -> Unit = {},
    ): Waveform? = withContext(Dispatchers.Default) {
        val cacheFile = waveformCacheFile(context, uri)
        cacheFile?.let { readCachedWaveform(it, buckets) }?.let { return@withContext it }

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return@withContext null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            if (durationUs <= 0) return@withContext null
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return@withContext null

            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()

            val peaks = FloatArray(buckets)
            val pcm = PcmShape(format.getIntegerOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: 2)
            var lastPartial = System.currentTimeMillis()
            fun maybePublish() {
                val now = System.currentTimeMillis()
                if (now - lastPartial >= 300) {
                    lastPartial = now
                    onPartial(Waveform(normalised(peaks), durationUs / 1000))
                }
            }

            if (durationUs <= FULL_DECODE_LIMIT_US) {
                decodeRun(decoder, extractor, pcm, maxPackets = Int.MAX_VALUE) { timeUs, peak ->
                    val bucket = ((timeUs.toDouble() / durationUs) * buckets).toInt().coerceIn(0, buckets - 1)
                    if (peak > peaks[bucket]) peaks[bucket] = peak
                    maybePublish()
                }
            } else {
                for (i in 0 until buckets) {
                    ensureActive()
                    val timeUs = (durationUs.toDouble() * (i + 0.5) / buckets).toLong()
                    extractor.seekTo(timeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    decoder.flush()
                    decodeRun(decoder, extractor, pcm, maxPackets = PACKETS_PER_POINT) { _, peak ->
                        if (peak > peaks[i]) peaks[i] = peak
                    }
                    maybePublish()
                }
            }

            // Buckets the decoder's timestamps skipped borrow their neighbour's value.
            for (i in 1 until buckets) if (peaks[i] == 0f) peaks[i] = peaks[i - 1] * 0.98f
            val result = Waveform(normalised(peaks), durationUs / 1000)
            cacheFile?.let { writeCachedWaveform(it, result) }
            result
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't read waveform for $uri", e)
            null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    /** What the decoder's output looks like; learnt from its first format change. */
    private class PcmShape(var channels: Int, var float: Boolean = false)

    /**
     * Feeds the decoder up to [maxPackets] packets from the extractor's current position
     * and reports the peak of each decoded buffer. Inputs are queued while the decoder will
     * take them and outputs drained as soon as they're ready; the only waiting is a short
     * poll when it has nothing to give yet.
     */
    private fun CoroutineScope.decodeRun(
        decoder: MediaCodec,
        extractor: MediaExtractor,
        pcm: PcmShape,
        maxPackets: Int,
        onPeak: (timeUs: Long, peak: Float) -> Unit,
    ) {
        val info = MediaCodec.BufferInfo()
        var queued = 0
        var inputDone = false
        var drained = 0
        var idlePolls = 0
        while (true) {
            ensureActive()
            // A decoder that has gone silent for ~1 s isn't going to say more.
            if (idlePolls > 500) return
            while (!inputDone) {
                val inIndex = decoder.dequeueInputBuffer(0)
                if (inIndex < 0) break
                val buffer = decoder.getInputBuffer(inIndex) ?: break
                val size = if (queued >= maxPackets) -1 else extractor.readSampleData(buffer, 0)
                if (size < 0) {
                    decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputDone = true
                } else {
                    decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                    extractor.advance()
                    queued++
                }
            }
            val outIndex = decoder.dequeueOutputBuffer(info, 2_000)
            if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) idlePolls++ else idlePolls = 0
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val out = decoder.outputFormat
                    pcm.float = out.getIntegerOrNull(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    pcm.channels = out.getIntegerOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: pcm.channels
                }
                outIndex >= 0 -> {
                    val out = decoder.getOutputBuffer(outIndex)
                    if (out != null && info.size > 0) {
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        onPeak(info.presentationTimeUs, peakOf(out.order(ByteOrder.nativeOrder()), pcm.float, pcm.channels))
                    }
                    decoder.releaseOutputBuffer(outIndex, false)
                    drained++
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                // Sampled mode: some decoders hold the last frames back; once the inputs are
                // in and the queue has gone quiet, the point has what it needs.
                inputDone && maxPackets != Int.MAX_VALUE && drained >= queued - 2 -> return
            }
        }
    }

    private fun normalised(peaks: FloatArray): FloatArray {
        val loudest = peaks.maxOrNull()?.takeIf { it > 0f } ?: 1f
        return FloatArray(peaks.size) { (peaks[it] / loudest).coerceIn(0f, 1f) }
    }

    /** Cache file for this exact version of the file, or null if its size can't be read. */
    private fun waveformCacheFile(context: Context, uri: Uri): File? = runCatching {
        val size = context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: return null
        val key = "${uri}#$size".hashCode().toUInt().toString(16)
        File(File(context.cacheDir, "waveforms").apply { mkdirs() }, "$key.wf")
    }.getOrNull()

    private fun readCachedWaveform(file: File, buckets: Int): Waveform? = runCatching {
        if (!file.exists()) return null
        java.io.DataInputStream(file.inputStream().buffered()).use { input ->
            val duration = input.readLong()
            val count = input.readInt()
            if (count != buckets || duration <= 0) return null
            Waveform(FloatArray(count) { input.readFloat() }, duration)
        }
    }.getOrNull()

    private fun writeCachedWaveform(file: File, waveform: Waveform) {
        runCatching {
            java.io.DataOutputStream(file.outputStream().buffered()).use { out ->
                out.writeLong(waveform.durationMs)
                out.writeInt(waveform.peaks.size)
                waveform.peaks.forEach { out.writeFloat(it) }
            }
        }
    }

    private fun peakOf(buffer: ByteBuffer, floatPcm: Boolean, channels: Int): Float {
        var peak = 0f
        if (floatPcm) {
            val floats = buffer.asFloatBuffer()
            var i = 0
            while (i < floats.limit()) {
                peak = max(peak, abs(floats.get(i)))
                i += channels * 8
            }
        } else {
            val shorts = buffer.asShortBuffer()
            var i = 0
            while (i < shorts.limit()) {
                peak = max(peak, abs(shorts.get(i).toInt()) / 32768f)
                i += channels * 8
            }
        }
        return peak
    }

    private fun MediaFormat.getIntegerOrNull(key: String): Int? =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

    // --- Export --------------------------------------------------------------------

    /**
     * Writes the trimmed audio to a new file in app storage and returns it. The caller
     * moves it into place. [onProgress] gets 0..100.
     */
    suspend fun export(
        context: Context,
        uri: Uri,
        durationMs: Long,
        plan: TrimPlan,
        onProgress: (Int) -> Unit,
    ): File {
        val pieces = segments(plan, durationMs)
        require(pieces.isNotEmpty()) { "Nothing would be left of the song." }

        val items = pieces.map { range ->
            val startMs = range.first
            val endMs = range.last + 1
            val fadeIn = when {
                startMs <= 0L -> 0L
                plan.mode == TrimMode.KEEP -> EDGE_FADE_MS
                else -> JOIN_FADE_MS
            }
            val fadeOut = when {
                endMs >= durationMs -> 0L
                plan.mode == TrimMode.KEEP -> EDGE_FADE_MS
                else -> JOIN_FADE_MS
            }
            val clip = MediaItem.Builder()
                .setUri(uri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(startMs)
                        .setEndPositionMs(endMs)
                        .build(),
                )
                .build()
            EditedMediaItem.Builder(clip)
                .setRemoveVideo(true)
                .setEffects(
                    Effects(
                        listOf<AudioProcessor>(EdgeFadeProcessor(fadeIn, fadeOut, endMs - startMs)),
                        emptyList(),
                    ),
                )
                .build()
        }
        val composition = Composition.Builder(
            EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO)).addItems(items).build(),
        ).build()
        val output = File(File(context.cacheDir, "trim").apply { mkdirs() }, "trim_${System.nanoTime()}.m4a")

        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val transformer = Transformer.Builder(context)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(
                        DefaultEncoderFactory.Builder(context)
                            .setRequestedAudioEncoderSettings(
                                AudioEncoderSettings.Builder().setBitrate(BITRATE).build(),
                            )
                            .build(),
                    )
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (cont.isActive) cont.resume(output)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException,
                        ) {
                            output.delete()
                            if (cont.isActive) cont.resumeWithException(exportException)
                        }
                    })
                    .build()
                transformer.start(composition, output.absolutePath)

                val holder = ProgressHolder()
                val poller = kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
                    while (cont.isActive) {
                        if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                            onProgress(holder.progress)
                        }
                        delay(200)
                    }
                }
                // One handler only: a continuation refuses a second invokeOnCancellation
                // with IllegalStateException, which is what broke every save.
                cont.invokeOnCancellation {
                    poller.cancel()
                    transformer.cancel()
                    output.delete()
                }
            }
        }
    }
}

/**
 * Fades the first [fadeInMs] in and the last [fadeOutMs] out of one clip, so the edges
 * of a trim and the join of a cut don't click. Handles 16-bit and float PCM; anything
 * else passes through untouched.
 */
@OptIn(UnstableApi::class)
private class EdgeFadeProcessor(
    private val fadeInMs: Long,
    private val fadeOutMs: Long,
    private val clipDurationMs: Long,
) : BaseAudioProcessor() {

    private var framesSeen = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (fadeInMs <= 0 && fadeOutMs <= 0) return AudioProcessor.AudioFormat.NOT_SET
        val ok = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT ||
            inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        return if (ok) inputAudioFormat else AudioProcessor.AudioFormat.NOT_SET
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        framesSeen = 0
    }

    override fun onReset() {
        framesSeen = 0
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val format = inputAudioFormat
        val bytesPerSample = if (format.encoding == C.ENCODING_PCM_FLOAT) 4 else 2
        val frameBytes = bytesPerSample * format.channelCount
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val out = replaceOutputBuffer(remaining).order(inputBuffer.order())
        val rate = format.sampleRate.toDouble()
        val totalFrames = (clipDurationMs / 1000.0 * rate).toLong()
        val fadeInFrames = (fadeInMs / 1000.0 * rate).toLong()
        val fadeOutFrames = (fadeOutMs / 1000.0 * rate).toLong()

        while (inputBuffer.remaining() >= frameBytes) {
            val frame = framesSeen++
            var gain = 1.0
            if (fadeInFrames > 0 && frame < fadeInFrames) gain = frame.toDouble() / fadeInFrames
            val fromEnd = totalFrames - frame
            if (fadeOutFrames > 0 && fromEnd < fadeOutFrames) gain = minOf(gain, (fromEnd.coerceAtLeast(0)).toDouble() / fadeOutFrames)
            repeat(format.channelCount) {
                if (bytesPerSample == 4) {
                    out.putFloat((inputBuffer.float * gain).toFloat())
                } else {
                    out.putShort((inputBuffer.short * gain).toInt().toShort())
                }
            }
        }
        while (inputBuffer.hasRemaining()) out.put(inputBuffer.get())
        out.flip()
    }
}
