package com.andres.musicplayer.data.tags

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * Writes iTunes-style metadata into MP4/M4A files behind SAF document uris — the
 * counterpart to [com.andres.musicplayer.data.id3.Id3Tag], which only understands MP3.
 *
 * MP4 has no ID3 tag. Metadata lives in a box tree, `moov → udta → meta → ilst`, where
 * each item is a four-character atom (`©nam` for title, `©ART` for artist, `©alb` for
 * album, `covr` for cover art) wrapping a `data` box. Anything already in `ilst` that
 * this class doesn't set is copied through untouched, matching Id3Tag's behaviour.
 *
 * ### Why the new `moov` is appended rather than resized in place
 *
 * The `stco`/`co64` tables inside `moov` hold *absolute file offsets* of the audio
 * chunks. Growing `moov` in place would shift `mdat` and invalidate every one of them,
 * so a naive rewrite silently produces a file that no longer plays.
 *
 * Rather than rewriting those tables, this keeps `mdat` exactly where it is:
 * - if `moov` is already the last box, the file is truncated there and the new `moov`
 *   written at the same offset — nothing else moves;
 * - otherwise the old `moov` is overwritten with a `free` box of *identical size* and
 *   the new `moov` appended at the end. Byte offsets of everything else are unchanged,
 *   so the chunk tables stay correct.
 *
 * The first edit of a front-`moov` file therefore leaves one dead `free` box behind;
 * every later edit rewrites the trailing `moov` in place and costs nothing.
 */
object Mp4Tag {

    /**
     * Container suffixes this writer understands, lowercase.
     *
     * The single authority on the question, deliberately: the download engine has to know
     * which containers are worth tagging, and the stream resolver has to know which
     * container to *ask YouTube for* in the first place. Those two lists silently drifting
     * apart is exactly how downloads ended up arriving as untaggable webm.
     */
    internal val EXTENSIONS = setOf("m4a", "m4b", "mp4", "aac")

    /** Atom names for the fields this app edits. `©` is byte 0xA9. */
    private const val ATOM_TITLE = "©nam"
    private const val ATOM_ARTIST = "©ART"
    private const val ATOM_ALBUM = "©alb"
    private const val ATOM_COVER = "covr"

    /** `data` box type flags, from the QuickTime/iTunes metadata spec. */
    private const val DATA_TYPE_UTF8 = 1
    private const val DATA_TYPE_JPEG = 13
    private const val DATA_TYPE_PNG = 14

    /**
     * Updates the file's metadata in place. Non-null [title]/[artist]/[album] replace
     * those atoms; null leaves them untouched. Returns true on success.
     *
     * Returns false rather than throwing for anything it doesn't fully understand, so a
     * caller can treat it exactly like [com.andres.musicplayer.data.id3.Id3Tag.writeTags]:
     * false means "couldn't persist to file", never "file was damaged".
     */
    fun writeTags(
        context: Context,
        uriString: String,
        title: String? = null,
        artist: String? = null,
        album: String? = null,
        artwork: ByteArray? = null,
        artworkMime: String? = null,
    ): Boolean {
        if (title == null && artist == null && album == null && artwork == null) return true

        val uri = Uri.parse(uriString)
        val resolver = context.contentResolver
        val working = File.createTempFile("mp4tag", ".tmp", context.cacheDir)
        return try {
            resolver.openInputStream(uri)?.use { input ->
                working.outputStream().use { input.copyTo(it) }
            } ?: return false

            if (!rewrite(working, title, artist, album, artwork, artworkMime)) return false

            resolver.openOutputStream(uri, "wt")?.use { out ->
                working.inputStream().use { it.copyTo(out) }
            } ?: return false
            true
        } catch (_: Exception) {
            false
        } finally {
            working.delete()
        }
    }

    /**
     * Applies the edit to [file] in place. Returns false if the layout isn't understood.
     *
     * Internal rather than private so it can be unit-tested without an Android context —
     * a bug here corrupts audio files, which is not something to find out on a device.
     */
    internal fun rewrite(
        file: File,
        title: String?,
        artist: String?,
        album: String?,
        artwork: ByteArray?,
        artworkMime: String?,
    ): Boolean {
        RandomAccessFile(file, "rw").use { raf ->
            val boxes = topLevelBoxes(raf) ?: return false
            val moovIndex = boxes.indexOfFirst { it.type == "moov" }
            if (moovIndex < 0) return false
            val moov = boxes[moovIndex]

            // moov is small (tens of KB); reading it whole keeps the edit simple.
            if (moov.size > MAX_MOOV_BYTES) return false
            val moovBytes = ByteArray(moov.size.toInt())
            raf.seek(moov.offset)
            raf.readFully(moovBytes)

            val updated = updateMoov(moovBytes, title, artist, album, artwork, artworkMime)
                ?: return false

            // Fragmented files (every YouTube DASH download) need moov in front of the
            // fragments, so the append-at-the-end trick below would break them.
            if (boxes.any { it.type == "moof" }) {
                return relayoutFragmented(raf, file, boxes, moovIndex, updated)
            }

            val moovIsLast = boxes.drop(moovIndex + 1).all { it.type == "free" || it.type == "skip" }
            when {
                // Same size: overwrite where it stands, nothing moves.
                updated.size.toLong() == moov.size -> {
                    raf.seek(moov.offset)
                    raf.write(updated)
                }
                // Nothing of substance after moov: drop the tail and rewrite it there.
                moovIsLast -> {
                    raf.seek(moov.offset)
                    raf.write(updated)
                    raf.setLength(moov.offset + updated.size)
                }
                // Otherwise blank out the old moov and append the new one, so that every
                // byte offset recorded in stco/co64 still points at the right chunk.
                else -> {
                    raf.seek(moov.offset)
                    raf.write(freeBox(moov.size))
                    raf.seek(raf.length())
                    raf.write(updated)
                }
            }
        }
        return true
    }

    /**
     * Rewrites a fragmented MP4 as `ftyp, moov, sidx, moof, mdat, …` with the new [moov].
     *
     * A fragmented file has no `stco` offsets to protect, which is the only reason the
     * non-fragmented path refuses to move `mdat`. What it has instead is a hard ordering
     * rule: players read `moov` before the first `moof`, and any fragment that arrives
     * before the track list is skipped. Appending `moov` at the end — what this writer
     * used to do to every file — produces a track that plays silence from the start and
     * only makes sound once a seek jumps through `sidx` to a fragment it can now decode.
     *
     * Shifting the fragments is safe because everything inside them is relative: `sidx`
     * points forward from its own end, and `trun` data offsets count from their `moof`.
     * The two exceptions are handled explicitly: `mfra` (an optional random-access index of
     * absolute offsets) is dropped, and a `tfhd` carrying an absolute base offset makes the
     * edit refuse rather than corrupt the file.
     *
     * Also repairs files damaged by the old behaviour, whose `moov` sits at the very end
     * behind a dead `free` box: those leading `free` boxes are dropped on the way through.
     */
    private fun relayoutFragmented(
        raf: RandomAccessFile,
        file: File,
        boxes: List<Box>,
        moovIndex: Int,
        moov: ByteArray,
    ): Boolean {
        if (boxes.any { it.type == "moof" && hasAbsoluteBaseOffset(raf, it) }) return false
        if (hasChunkOffsets(moov)) return false

        val firstMedia = boxes.indexOfFirst { it.type in FRAGMENT_START_TYPES }
        val temp = File(file.parentFile, file.name + ".relayout")
        try {
            temp.outputStream().buffered().use { out ->
                boxes.forEachIndexed { index, box ->
                    if (index == firstMedia) out.write(moov)
                    val dropped = index == moovIndex ||
                        box.type == "mfra" ||
                        (index < firstMedia && (box.type == "free" || box.type == "skip"))
                    if (!dropped) copyRange(raf, box.offset, box.size, out)
                }
            }
            raf.seek(0)
            temp.inputStream().use { input ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    raf.write(buffer, 0, read)
                }
            }
            raf.setLength(temp.length())
        } finally {
            temp.delete()
        }
        return true
    }

    /**
     * True when [file] is a fragmented MP4 whose `moov` is not ahead of its fragments —
     * the layout the old writer left behind, which plays silently until the user seeks.
     */
    internal fun needsFragmentRelayout(file: File): Boolean = runCatching {
        RandomAccessFile(file, "r").use { raf -> needsFragmentRelayout(topLevelBoxes(raf)) }
    }.getOrDefault(false)

    private fun needsFragmentRelayout(boxes: List<Box>?): Boolean {
        if (boxes == null) return false
        val moov = boxes.indexOfFirst { it.type == "moov" }
        val firstMedia = boxes.indexOfFirst { it.type in FRAGMENT_START_TYPES }
        return moov >= 0 && firstMedia >= 0 && boxes.any { it.type == "moof" } && moov > firstMedia
    }

    /**
     * Fixes a library file the old writer left with `moov` behind its fragments. Only the
     * top-level box headers are read to decide, so calling this on a healthy file is cheap
     * and changes nothing. Returns true only if the file was actually rewritten.
     */
    fun repairFragmentedLayout(context: Context, uriString: String): Boolean {
        val uri = Uri.parse(uriString)
        val resolver = context.contentResolver
        // Header-only check straight off the document, so a healthy library is never copied.
        val needsRepair = runCatching {
            resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                java.io.FileInputStream(pfd.fileDescriptor).channel.use { channel ->
                    needsFragmentRelayout(
                        topLevelBoxes(channel.size()) { position, buffer ->
                            val wrapped = java.nio.ByteBuffer.wrap(buffer)
                            while (wrapped.hasRemaining()) {
                                if (channel.read(wrapped, position + wrapped.position()) < 0) {
                                    throw java.io.EOFException()
                                }
                            }
                        },
                    )
                }
            }
        }.getOrNull() ?: false
        if (!needsRepair) return false

        val working = File.createTempFile("mp4fix", ".tmp", context.cacheDir)
        return try {
            resolver.openInputStream(uri)?.use { input ->
                working.outputStream().use { input.copyTo(it) }
            } ?: return false
            // Same moov contents; the rewrite exists for its relayout side effect.
            if (!rewrite(working, null, null, null, null, null)) return false
            resolver.openOutputStream(uri, "wt")?.use { out ->
                working.inputStream().use { it.copyTo(out) }
            } ?: return false
            true
        } catch (_: Exception) {
            false
        } finally {
            working.delete()
        }
    }

    /** `tfhd` flag 0x000001: the fragment's data is addressed from an absolute file offset. */
    private fun hasAbsoluteBaseOffset(raf: RandomAccessFile, moof: Box): Boolean {
        if (moof.size > MAX_MOOV_BYTES) return true
        val bytes = ByteArray(moof.size.toInt())
        raf.seek(moof.offset)
        raf.readFully(bytes)
        val trafs = parseChildren(bytes, 8, bytes.size)?.filter { it.type == "traf" } ?: return true
        return trafs.any { traf ->
            val tfhd = parseChildren(bytes, traf.offset + 8, traf.offset + traf.size)
                ?.firstOrNull { it.type == "tfhd" }
                ?: return@any true
            (readUInt32(bytes, tfhd.offset + 8) and 0x000001L) != 0L
        }
    }

    /** True if any `stco`/`co64` in [moov] has entries — absolute offsets a shift would break. */
    private fun hasChunkOffsets(moov: ByteArray): Boolean {
        fun walk(from: Int, to: Int): Boolean {
            val children = parseChildren(moov, from, to) ?: return true
            return children.any { child ->
                when (child.type) {
                    "trak", "mdia", "minf", "stbl" -> walk(child.offset + 8, child.offset + child.size)
                    // FullBox header (4 bytes), then a 32-bit entry count.
                    "stco", "co64" -> readUInt32(moov, child.offset + 12) > 0
                    else -> false
                }
            }
        }
        return walk(8, moov.size)
    }

    private fun copyRange(raf: RandomAccessFile, offset: Long, size: Long, out: java.io.OutputStream) {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        raf.seek(offset)
        var remaining = size
        while (remaining > 0) {
            val read = raf.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
            if (read == -1) throw java.io.EOFException("Box ran past the end of the file")
            out.write(buffer, 0, read)
            remaining -= read
        }
    }

    /**
     * Size in bytes of the cover image actually present in [file], or null if it has none.
     *
     * A diagnostic, not part of writing: [rewrite] returning true only says the write path
     * ran to completion, which is not the same claim as "the `covr` atom is in the file and
     * can be found again". Reading it back is the difference between those two, and it is
     * cheap — only `moov` is touched, never the audio payload.
     */
    internal fun coverArtSize(file: File): Int? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val moov = topLevelBoxes(raf)?.firstOrNull { it.type == "moov" } ?: return null
            if (moov.size > MAX_MOOV_BYTES) return null
            val moovBytes = ByteArray(moov.size.toInt())
            raf.seek(moov.offset)
            raf.readFully(moovBytes)

            val children = parseChildren(moovBytes, 8, moovBytes.size) ?: return null
            val udta = children.firstOrNull { it.type == "udta" } ?: return null
            val ilst = findIlst(moovBytes, udta) ?: return null
            val payload = parseIlstItems(moovBytes, ilst)[ATOM_COVER] ?: return null
            // The payload is the whole `data` box: its 8-byte header, then 8 bytes of
            // version/type/locale, then the image.
            (payload.size - DATA_BOX_OVERHEAD).takeIf { it > 0 }
        }
    }.getOrNull()

    // --- Box tree ------------------------------------------------------------------

    private class Box(val type: String, val offset: Long, val size: Long)

    /** Reads the top-level box list, or null if the file isn't a sane MP4. */
    private fun topLevelBoxes(raf: RandomAccessFile): List<Box>? =
        topLevelBoxes(raf.length()) { position, buffer ->
            raf.seek(position)
            raf.readFully(buffer)
        }

    /** Same walk over any random-access source: [readAt] fills the buffer from a position. */
    private fun topLevelBoxes(length: Long, readAt: (Long, ByteArray) -> Unit): List<Box>? {
        val boxes = mutableListOf<Box>()
        var offset = 0L
        val header = ByteArray(8)
        while (offset + 8 <= length) {
            readAt(offset, header)
            var size = readUInt32(header, 0)
            val type = String(header, 4, 4, Charsets.ISO_8859_1)
            var headerSize = 8L
            when (size) {
                // 1 means the real size is in a 64-bit field after the type.
                1L -> {
                    val large = ByteArray(8)
                    readAt(offset + 8, large)
                    size = readUInt64(large)
                    headerSize = 16L
                }
                // 0 means "extends to end of file".
                0L -> size = length - offset
            }
            if (size < headerSize || offset + size > length) return null
            boxes += Box(type, offset, size)
            offset += size
        }
        return boxes.takeIf { list -> list.any { it.type == "ftyp" } }
    }

    /**
     * Rebuilds `moov` with the requested metadata, creating the
     * `udta → meta → ilst` chain if the file has none. Returns null if `moov`'s
     * internal structure doesn't parse.
     */
    private fun updateMoov(
        moov: ByteArray,
        title: String?,
        artist: String?,
        album: String?,
        artwork: ByteArray?,
        artworkMime: String?,
    ): ByteArray? {
        val children = parseChildren(moov, 8, moov.size) ?: return null

        val udta = children.firstOrNull { it.type == "udta" }
        val existingItems = udta
            ?.let { findIlst(moov, it) }
            ?.let { parseIlstItems(moov, it) }
            ?: emptyMap()

        val items = LinkedHashMap(existingItems)
        title?.let { items[ATOM_TITLE] = textData(it) }
        artist?.let { items[ATOM_ARTIST] = textData(it) }
        album?.let { items[ATOM_ALBUM] = textData(it) }
        artwork?.let { items[ATOM_COVER] = imageData(it, artworkMime) }

        // Everything except udta is copied through byte-for-byte.
        val out = ByteArrayOutputStream()
        out.write(ByteArray(8)) // placeholder for size + "moov", filled in below
        for (child in children) {
            if (child.type == "udta") continue
            out.write(moov, child.offset, child.size)
        }
        out.write(buildUdta(items))

        val result = out.toByteArray()
        writeUInt32(result, 0, result.size.toLong())
        System.arraycopy("moov".toByteArray(Charsets.ISO_8859_1), 0, result, 4, 4)
        return result
    }

    private class Child(val type: String, val offset: Int, val size: Int)

    /** Parses the immediate children of a container between [from] and [to]. */
    private fun parseChildren(data: ByteArray, from: Int, to: Int): List<Child>? {
        val children = mutableListOf<Child>()
        var offset = from
        while (offset + 8 <= to) {
            val size = readUInt32(data, offset).toInt()
            val type = String(data, offset + 4, 4, Charsets.ISO_8859_1)
            // 64-bit sizes are not expected inside moov; bail rather than guess.
            if (size < 8 || offset + size > to) return null
            children += Child(type, offset, size)
            offset += size
        }
        return children
    }

    /** Locates `ilst` under `udta → meta`, if this file already has one. */
    private fun findIlst(moov: ByteArray, udta: Child): Child? {
        val udtaChildren = parseChildren(moov, udta.offset + 8, udta.offset + udta.size)
            ?: return null
        val meta = udtaChildren.firstOrNull { it.type == "meta" } ?: return null
        // meta is a FullBox: 4 bytes of version/flags before its children.
        val metaChildren = parseChildren(moov, meta.offset + 12, meta.offset + meta.size)
            ?: return null
        return metaChildren.firstOrNull { it.type == "ilst" }
    }

    /** Reads existing `ilst` entries as type → raw `data` payload (the inner boxes). */
    private fun parseIlstItems(moov: ByteArray, ilst: Child): Map<String, ByteArray> {
        val items = LinkedHashMap<String, ByteArray>()
        val entries = parseChildren(moov, ilst.offset + 8, ilst.offset + ilst.size)
            ?: return items
        for (entry in entries) {
            val payload = ByteArray(entry.size - 8)
            System.arraycopy(moov, entry.offset + 8, payload, 0, payload.size)
            items[entry.type] = payload
        }
        return items
    }

    // --- Builders ---------------------------------------------------------------------

    private fun buildUdta(items: Map<String, ByteArray>): ByteArray {
        val ilst = ByteArrayOutputStream()
        for ((type, payload) in items) {
            ilst.write(box(type, payload))
        }

        val meta = ByteArrayOutputStream()
        meta.write(byteArrayOf(0, 0, 0, 0)) // FullBox version + flags
        meta.write(buildHdlr())
        meta.write(box("ilst", ilst.toByteArray()))

        return box("udta", box("meta", meta.toByteArray()))
    }

    /** The `mdir`/`appl` handler iTunes-style metadata is expected to declare. */
    private fun buildHdlr(): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(byteArrayOf(0, 0, 0, 0)) // version + flags
        body.write(ByteArray(4)) // predefined
        body.write("mdir".toByteArray(Charsets.ISO_8859_1))
        body.write("appl".toByteArray(Charsets.ISO_8859_1))
        body.write(ByteArray(9)) // reserved + empty name
        return box("hdlr", body.toByteArray())
    }

    /** Wraps text in the `data` box an ilst entry expects. */
    private fun textData(text: String): ByteArray =
        box("data", dataPayload(DATA_TYPE_UTF8, text.toByteArray(Charsets.UTF_8)))

    private fun imageData(image: ByteArray, mime: String?): ByteArray {
        val type = if (mime?.contains("png", ignoreCase = true) == true) {
            DATA_TYPE_PNG
        } else {
            DATA_TYPE_JPEG
        }
        return box("data", dataPayload(type, image))
    }

    private fun dataPayload(type: Int, value: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0) // version
        out.write(byteArrayOf((type shr 16).toByte(), (type shr 8).toByte(), type.toByte()))
        out.write(ByteArray(4)) // locale / reserved
        out.write(value)
        return out.toByteArray()
    }

    private fun box(type: String, body: ByteArray): ByteArray {
        val result = ByteArray(8 + body.size)
        writeUInt32(result, 0, result.size.toLong())
        System.arraycopy(type.toByteArray(Charsets.ISO_8859_1), 0, result, 4, 4)
        System.arraycopy(body, 0, result, 8, body.size)
        return result
    }

    /** A padding box of exactly [size] bytes, used to blank out the old `moov`. */
    private fun freeBox(size: Long): ByteArray {
        val result = ByteArray(size.toInt())
        writeUInt32(result, 0, size)
        System.arraycopy("free".toByteArray(Charsets.ISO_8859_1), 0, result, 4, 4)
        return result
    }

    // --- Numbers ------------------------------------------------------------------------

    private fun readUInt32(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF) shl 24) or
            ((b[off + 1].toLong() and 0xFF) shl 16) or
            ((b[off + 2].toLong() and 0xFF) shl 8) or
            (b[off + 3].toLong() and 0xFF)

    private fun readUInt64(b: ByteArray): Long {
        var value = 0L
        for (i in 0 until 8) value = (value shl 8) or (b[i].toLong() and 0xFF)
        return value
    }

    private fun writeUInt32(b: ByteArray, off: Int, value: Long) {
        b[off] = (value shr 24).toByte()
        b[off + 1] = (value shr 16).toByte()
        b[off + 2] = (value shr 8).toByte()
        b[off + 3] = value.toByte()
    }

    /** Guard against a pathological file; a real audio `moov` is far smaller. */
    private const val MAX_MOOV_BYTES = 32L * 1024 * 1024

    /** `data` box header plus its version/type/locale prefix, ahead of the value itself. */
    private const val DATA_BOX_OVERHEAD = 16

    /** Boxes that begin the media part of a fragmented file; `moov` must precede them. */
    private val FRAGMENT_START_TYPES = setOf("sidx", "styp", "moof", "mdat", "emsg", "prft")

    private const val COPY_BUFFER_BYTES = 64 * 1024
}
