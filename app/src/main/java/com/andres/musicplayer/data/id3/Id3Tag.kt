package com.andres.musicplayer.data.id3

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

/**
 * A tiny, dependency-free ID3v2 read-modify-write helper for MP3 files behind SAF
 * document uris. It preserves every existing frame (album art, any TXXX frames written
 * by other tools, etc.) and only touches what we ask it to: the title/artist/album
 * text frames and the cover-art APIC frame.
 *
 * Writes are emitted as ID3v2.3 with UTF-16 text. Reads understand v2.3 and v2.4.
 * Only standard tags (no per-frame unsync / extended headers) are handled; anything
 * exotic is left alone rather than risking corruption.
 */
object Id3Tag {

    private class Frame(val id: String, val data: ByteArray)

    /**
     * Updates the file's tag in place. Non-null [title]/[artist]/[album] replace those
     * frames; null leaves them untouched. Returns true on success.
     *
     * Not all uris are writable (e.g. read-only grants, or MediaStore content uris):
     * callers should treat false as "couldn't persist to file".
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
        val uri = Uri.parse(uriString)
        val resolver = context.contentResolver
        val tempAudio = File.createTempFile("mp3audio", ".tmp", context.cacheDir)
        try {
            val frames = mutableListOf<Frame>()

            // Phase 1: read existing frames and copy the audio body to a temp file.
            resolver.openInputStream(uri).use { rawInput ->
                rawInput ?: return false.also { tempAudio.delete() }
                val input = BufferedInputStream(rawInput)
                val header = ByteArray(10)
                val read = readFully(input, header)
                val isId3 = read == 10 &&
                    header[0] == 'I'.code.toByte() &&
                    header[1] == 'D'.code.toByte() &&
                    header[2] == '3'.code.toByte()
                if (isId3) {
                    val major = header[3].toInt() and 0xFF
                    val flags = header[5].toInt() and 0xFF
                    val size = synchsafe(header, 6)
                    val body = ByteArray(size)
                    readFully(input, body)
                    // Only parse if there's no unsync (0x80) or extended header (0x40).
                    if (flags and 0xC0 == 0) frames += parseFrames(body, major)
                    FileOutputStream(tempAudio).use { input.copyTo(it) }
                } else {
                    // No ID3v2 at the front: those bytes are audio. Keep them.
                    FileOutputStream(tempAudio).use { out ->
                        if (read > 0) out.write(header, 0, read)
                        input.copyTo(out)
                    }
                }
            }

            // Phase 2: apply the requested changes to the frame list.
            if (title != null) frames.replaceText("TIT2", title)
            if (artist != null) frames.replaceText("TPE1", artist)
            if (album != null) frames.replaceText("TALB", album)
            if (artwork != null) {
                // Replace any existing embedded cover with the new front-cover picture.
                frames.removeAll { it.id == "APIC" }
                frames += Frame("APIC", buildApicData(artwork, artworkMime ?: "image/jpeg"))
            }

            // Phase 3: serialise the new tag and write tag + audio back over the file.
            val tagBytes = buildTag(frames)
            resolver.openOutputStream(uri, "wt").use { out ->
                out ?: return false
                out.write(tagBytes)
                FileInputStream(tempAudio).use { it.copyTo(out) }
                out.flush()
            }
            return true
        } catch (_: Exception) {
            return false
        } finally {
            tempAudio.delete()
        }
    }

    // --- reading ---

    private fun parseFrames(body: ByteArray, major: Int): List<Frame> {
        val frames = mutableListOf<Frame>()
        var i = 0
        while (i + 10 <= body.size) {
            if (body[i] == 0.toByte()) break // padding
            val id = String(body, i, 4, Charsets.ISO_8859_1)
            if (!id.all { it.isLetterOrDigit() }) break
            val size = if (major >= 4) synchsafe(body, i + 4) else plainSize(body, i + 4)
            if (size <= 0 || i + 10 + size > body.size) break
            frames += Frame(id, body.copyOfRange(i + 10, i + 10 + size))
            i += 10 + size
        }
        return frames
    }

    // --- writing ---

    private fun MutableList<Frame>.replaceText(id: String, text: String) {
        removeAll { it.id == id }
        add(Frame(id, buildTextData(text)))
    }

    private fun buildTag(frames: List<Frame>): ByteArray {
        val framesOut = ByteArrayOutputStream()
        for (frame in frames) {
            framesOut.write(frame.id.toByteArray(Charsets.ISO_8859_1), 0, 4)
            framesOut.write(toPlainSize(frame.data.size))
            framesOut.write(0); framesOut.write(0) // frame flags
            framesOut.write(frame.data)
        }
        val framesBytes = framesOut.toByteArray()
        val out = ByteArrayOutputStream()
        out.write('I'.code); out.write('D'.code); out.write('3'.code)
        out.write(3); out.write(0) // version 2.3.0
        out.write(0) // header flags
        out.write(toSynchsafe(framesBytes.size))
        out.write(framesBytes)
        return out.toByteArray()
    }

    /** Text frame: encoding 0x01 (UTF-16 + BOM), then the text, then a UTF-16 terminator. */
    private fun buildTextData(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x01)
        writeUtf16(out, text)
        return out.toByteArray()
    }

    /**
     * APIC (attached picture) frame body for a front cover:
     * encoding(1) + MIME(ISO-8859-1, null-terminated) + picture type(1=0x03 front
     * cover) + description(null-terminated, empty) + raw image bytes.
     */
    private fun buildApicData(image: ByteArray, mime: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x00) // text encoding: ISO-8859-1 (only used by the description)
        out.write(mime.toByteArray(Charsets.ISO_8859_1))
        out.write(0) // MIME terminator
        out.write(0x03) // picture type: cover (front)
        out.write(0) // empty description + terminator
        out.write(image)
        return out.toByteArray()
    }

    private fun writeUtf16(out: ByteArrayOutputStream, text: String) {
        out.write(0xFF); out.write(0xFE) // little-endian BOM
        out.write(text.toByteArray(Charsets.UTF_16LE))
        out.write(0); out.write(0) // terminator
    }

    // --- byte helpers ---

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) break
            off += n
        }
        return off
    }

    private fun synchsafe(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0x7F) shl 21) or
            ((b[off + 1].toInt() and 0x7F) shl 14) or
            ((b[off + 2].toInt() and 0x7F) shl 7) or
            (b[off + 3].toInt() and 0x7F)

    private fun toSynchsafe(value: Int): ByteArray = byteArrayOf(
        ((value shr 21) and 0x7F).toByte(),
        ((value shr 14) and 0x7F).toByte(),
        ((value shr 7) and 0x7F).toByte(),
        (value and 0x7F).toByte(),
    )

    private fun plainSize(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)

    private fun toPlainSize(value: Int): ByteArray = byteArrayOf(
        ((value shr 24) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )
}
