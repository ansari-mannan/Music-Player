package com.andres.musicplayer

import com.andres.musicplayer.data.tags.Mp4Tag
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Structural tests for the MP4/M4A tag writer.
 *
 * The property that matters most is the one asserted first: **`mdat` must not move**.
 * The chunk offset tables inside `moov` are absolute file offsets, so shifting the audio
 * payload by even one byte produces a file that parses but no longer plays — a failure
 * that would be invisible until playback.
 *
 * These run on the JVM against synthetic files, so they prove the box arithmetic is
 * right. They cannot prove Android's own parser is happy with the result; that still
 * needs a real device and a real file.
 */
class Mp4TagTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** One second of 440 Hz AAC from ffmpeg — see `a real m4a survives a full tag write`. */
    private val REAL_M4A = "sample.m4a"

    private val audioPayload = ByteArray(4096) { (it % 251).toByte() }

    @Test
    fun `mdat keeps its bytes and its absolute offset when moov is at the front`() {
        val file = write(buildMp4(moovFirst = true))
        val originalOffset = offsetOf(file.readBytes(), "mdat")

        assertTrue(Mp4Tag.rewrite(file, "Title", "Artist", "Album", null, null))

        val after = file.readBytes()
        assertEquals(
            "mdat moved — every chunk offset in moov is now wrong",
            originalOffset,
            offsetOf(after, "mdat"),
        )
        assertArrayEquals(audioPayload, payloadOf(after, "mdat"))
    }

    @Test
    fun `old moov is replaced by padding of exactly the same size`() {
        val file = write(buildMp4(moovFirst = true))
        val before = boxes(file.readBytes())
        val originalMoov = before.first { it.type == "moov" }

        assertTrue(Mp4Tag.rewrite(file, "Title", null, null, null, null))

        val after = boxes(file.readBytes())
        val replacement = after.first { it.offset == originalMoov.offset }
        assertEquals("free", replacement.type)
        assertEquals(originalMoov.size, replacement.size)
        // Exactly one live moov, appended at the end.
        assertEquals(1, after.count { it.type == "moov" })
        assertEquals(after.last().type, "moov")
    }

    @Test
    fun `moov already at the end is rewritten in place without appending`() {
        val file = write(buildMp4(moovFirst = false))
        assertTrue(Mp4Tag.rewrite(file, "Title", null, null, null, null))

        val after = boxes(file.readBytes())
        assertEquals(1, after.count { it.type == "moov" })
        assertEquals(0, after.count { it.type == "free" })
        assertEquals("moov", after.last().type)
    }

    @Test
    fun `repeated edits do not grow the file without bound`() {
        val file = write(buildMp4(moovFirst = true))
        assertTrue(Mp4Tag.rewrite(file, "One", null, null, null, null))
        val afterFirst = file.length()
        repeat(5) { assertTrue(Mp4Tag.rewrite(file, "Take $it", null, null, null, null)) }

        // The first edit relocates moov and leaves one free box; later edits rewrite the
        // trailing moov in place, so length stays within a title's worth of the first.
        assertTrue(
            "file grew from $afterFirst to ${file.length()} across repeated edits",
            file.length() <= afterFirst + 64,
        )
        assertEquals("Take 4", readItem(file.readBytes(), "©nam"))
    }

    @Test
    fun `writes title artist and album as readable ilst items`() {
        val file = write(buildMp4(moovFirst = true))
        assertTrue(Mp4Tag.rewrite(file, "Around the World", "Daft Punk", "Homework", null, null))

        val bytes = file.readBytes()
        assertEquals("Around the World", readItem(bytes, "©nam"))
        assertEquals("Daft Punk", readItem(bytes, "©ART"))
        assertEquals("Homework", readItem(bytes, "©alb"))
    }

    @Test
    fun `null fields leave existing values alone`() {
        val file = write(buildMp4(moovFirst = true))
        assertTrue(Mp4Tag.rewrite(file, "First", "Artist", "Album", null, null))
        assertTrue(Mp4Tag.rewrite(file, "Second", null, null, null, null))

        val bytes = file.readBytes()
        assertEquals("Second", readItem(bytes, "©nam"))
        assertEquals("Artist", readItem(bytes, "©ART"))
        assertEquals("Album", readItem(bytes, "©alb"))
    }

    @Test
    fun `unrelated existing metadata survives a write`() {
        // A comment atom this app never touches should still be there afterwards.
        val file = write(buildMp4(moovFirst = true, extraItem = "©cmt" to "keep me"))
        assertTrue(Mp4Tag.rewrite(file, "New Title", null, null, null, null))

        val bytes = file.readBytes()
        assertEquals("New Title", readItem(bytes, "©nam"))
        assertEquals("keep me", readItem(bytes, "©cmt"))
    }

    @Test
    fun `artwork is stored in the cover atom`() {
        val file = write(buildMp4(moovFirst = true))
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
        assertTrue(Mp4Tag.rewrite(file, null, null, null, jpeg, "image/jpeg"))

        assertArrayEquals(jpeg, readItemBytes(file.readBytes(), "covr"))
    }

    @Test
    fun `cover art does not move mdat and leaves a well formed file`() {
        // The dangerous case for artwork specifically: a JPEG is orders of magnitude
        // bigger than a title, so moov grows by far more than a text edit ever does.
        val file = write(buildMp4(moovFirst = true))
        val originalOffset = offsetOf(file.readBytes(), "mdat")
        val jpeg = fakeJpeg(96 * 1024)

        assertTrue(Mp4Tag.rewrite(file, "Title", "Artist", null, jpeg, "image/jpeg"))

        val after = file.readBytes()
        assertEquals(
            "mdat moved — every chunk offset in moov is now wrong",
            originalOffset,
            offsetOf(after, "mdat"),
        )
        assertArrayEquals(audioPayload, payloadOf(after, "mdat"))
        assertArrayEquals("cover art did not round-trip", jpeg, readItemBytes(after, "covr"))
        assertEquals("Title", readItem(after, "©nam"))

        val parsed = boxes(after)
        assertEquals(
            "boxes must tile the file exactly with no gaps or overruns",
            file.length(),
            parsed.sumOf { it.size },
        )
        assertEquals("ftyp", parsed.first().type)
        assertEquals(1, parsed.count { it.type == "moov" })
        assertEquals("moov", parsed.last().type)
    }

    @Test
    fun `cover art embedded at download time survives a later tag edit`() {
        // Downloads write art; "save song info to files" later writes text into the same
        // file and must copy the art through rather than dropping it.
        val file = write(buildMp4(moovFirst = true))
        val originalOffset = offsetOf(file.readBytes(), "mdat")
        val jpeg = fakeJpeg(32 * 1024)
        assertTrue(Mp4Tag.rewrite(file, null, null, null, jpeg, "image/jpeg"))

        assertTrue(Mp4Tag.rewrite(file, "Later Title", "Later Artist", "Later Album", null, null))

        val after = file.readBytes()
        assertArrayEquals(jpeg, readItemBytes(after, "covr"))
        assertEquals("Later Title", readItem(after, "©nam"))
        assertEquals(originalOffset, offsetOf(after, "mdat"))
    }

    @Test
    fun `replacing cover art does not stack two copies of the image`() {
        val file = write(buildMp4(moovFirst = true))
        assertTrue(Mp4Tag.rewrite(file, null, null, null, fakeJpeg(32 * 1024), "image/jpeg"))
        val afterFirst = file.length()

        val replacement = fakeJpeg(32 * 1024, seed = 7)
        assertTrue(Mp4Tag.rewrite(file, null, null, null, replacement, "image/jpeg"))

        assertArrayEquals(replacement, readItemBytes(file.readBytes(), "covr"))
        assertEquals(
            "a second image of the same size should not change the file length",
            afterFirst,
            file.length(),
        )
    }

    @Test
    fun `the cover art read-back reports what is really in the file`() {
        // What the download log relies on: `rewrite` returning true says the write path
        // ran, and this says the atom can be found again. Reporting a size for a file
        // with no art — or none for a file that has it — would make the log a liar.
        val file = write(buildMp4(moovFirst = true))
        assertNull("a fresh file has no cover", Mp4Tag.coverArtSize(file))

        assertTrue(Mp4Tag.rewrite(file, "Title", null, null, null, null))
        assertNull("text tags alone must not look like art", Mp4Tag.coverArtSize(file))

        val jpeg = fakeJpeg(32 * 1024)
        assertTrue(Mp4Tag.rewrite(file, null, null, null, jpeg, "image/jpeg"))
        assertEquals(jpeg.size, Mp4Tag.coverArtSize(file))

        // And it must keep tracking the file after a later text-only edit moves moov.
        assertTrue(Mp4Tag.rewrite(file, "Later Title", null, null, null, null))
        assertEquals(jpeg.size, Mp4Tag.coverArtSize(file))
    }

    @Test
    fun `the cover art read-back refuses a file it cannot parse`() {
        assertNull(Mp4Tag.coverArtSize(write("not an mp4 at all".toByteArray())))
        assertNull(Mp4Tag.coverArtSize(write(box("ftyp", "M4A ".toByteArray()))))
    }

    @Test
    fun `refuses a file that is not an mp4 instead of mangling it`() {
        val file = write("this is definitely not an mp4 container".toByteArray())
        val before = file.readBytes()

        assertFalse(Mp4Tag.rewrite(file, "Title", null, null, null, null))
        assertArrayEquals("a rejected file must be left untouched", before, file.readBytes())
    }

    @Test
    fun `refuses an mp4 with no moov box`() {
        val file = write(box("ftyp", "M4A ".toByteArray()) + box("mdat", audioPayload))
        assertFalse(Mp4Tag.rewrite(file, "Title", null, null, null, null))
    }

    @Test
    fun `result is still a well formed box tree`() {
        val file = write(buildMp4(moovFirst = true))
        assertTrue(Mp4Tag.rewrite(file, "Title", "Artist", "Album", null, null))

        val parsed = boxes(file.readBytes())
        assertEquals(
            "boxes must tile the file exactly with no gaps or overruns",
            file.length(),
            parsed.sumOf { it.size },
        )
        assertEquals("ftyp", parsed.first().type)
    }

    // --- Against a real encoder's output -------------------------------------------------

    /**
     * The synthetic fixtures above are built by the same understanding of MP4 that the
     * writer holds, so a shared misconception would pass every one of them. This one is a
     * second of 440 Hz AAC straight out of ffmpeg — a real encoder's box layout, with a
     * real `stco` table whose offsets break for real if `mdat` moves.
     */
    @Test
    fun `a real m4a survives a full tag and cover write`() {
        val file = realM4a()
        val before = file.readBytes()
        val originalMdat = payloadOf(before, "mdat")
        val originalOffset = offsetOf(before, "mdat")
        val jpeg = fakeJpeg(24 * 1024)

        assertTrue(
            Mp4Tag.rewrite(file, "Test Tone", "Test Artist", "Test Album", jpeg, "image/jpeg"),
        )

        val after = file.readBytes()
        assertEquals(
            "mdat moved — every chunk offset in this file's stco table is now wrong",
            originalOffset,
            offsetOf(after, "mdat"),
        )
        assertArrayEquals("the encoded audio itself must be untouched", originalMdat, payloadOf(after, "mdat"))

        assertEquals("Test Tone", readItem(after, "©nam"))
        assertEquals("Test Artist", readItem(after, "©ART"))
        assertEquals("Test Album", readItem(after, "©alb"))
        assertArrayEquals(jpeg, readItemBytes(after, "covr"))
        assertEquals(jpeg.size, Mp4Tag.coverArtSize(file))

        val parsed = boxes(after)
        assertEquals(
            "boxes must tile the file exactly with no gaps or overruns",
            file.length(),
            parsed.sumOf { it.size },
        )
        assertEquals("ftyp", parsed.first().type)
        assertEquals(1, parsed.count { it.type == "moov" })
    }

    @Test
    fun `a real m4a can be re-tagged repeatedly without damage`() {
        // What actually happens to a downloaded file: art at download time, then "save
        // song info to files" later. The audio has to survive both.
        val file = realM4a()
        val originalMdat = payloadOf(file.readBytes(), "mdat")
        val jpeg = fakeJpeg(16 * 1024)

        assertTrue(Mp4Tag.rewrite(file, "First", "Artist", null, jpeg, "image/jpeg"))
        repeat(4) { assertTrue(Mp4Tag.rewrite(file, "Take $it", null, null, null, null)) }

        val after = file.readBytes()
        assertArrayEquals(originalMdat, payloadOf(after, "mdat"))
        assertEquals("Take 3", readItem(after, "©nam"))
        assertEquals("Artist", readItem(after, "©ART"))
        assertArrayEquals("the cover must ride through every later edit", jpeg, readItemBytes(after, "covr"))
    }

    @Test
    fun `the real fixture is what it claims to be`() {
        // If the resource went missing or were replaced by something else, the two tests
        // above would fail for reasons that had nothing to do with the tag writer.
        val parsed = boxes(realM4a().readBytes())
        assertTrue("expected an mp4 box tree", parsed.map { it.type }.containsAll(listOf("ftyp", "moov", "mdat")))
        assertNull("the fixture must start out untagged", readItem(realM4a().readBytes(), "©nam"))
        assertNull(Mp4Tag.coverArtSize(realM4a()))
    }

    // --- Fragmented MP4 (what YouTube actually serves) ------------------------------------

    /**
     * YouTube's m4a audio is fragmented: `ftyp, moov, sidx, moof, mdat, moof, mdat…`.
     * Players read `moov` before the first `moof`; a fragment seen before the track list is
     * skipped. The old writer appended `moov` at the end, which played silence from the
     * start and only made sound after a seek — this is that bug's regression test.
     */
    @Test
    fun `a fragmented m4a keeps moov ahead of its fragments`() {
        val file = fragmentedM4a()
        val before = file.readBytes()
        val media = fragmentRegion(before)
        val jpeg = fakeJpeg(24 * 1024)

        assertTrue(Mp4Tag.rewrite(file, "Tone", "Artist", "Album", jpeg, "image/jpeg"))

        val after = file.readBytes()
        val types = boxes(after).map { it.type }
        assertEquals(listOf("ftyp", "moov", "sidx", "moof"), types.take(4))
        assertEquals(1, types.count { it == "moov" })
        assertFalse("mfra holds absolute offsets and must not survive a shift", "mfra" in types)
        assertArrayEquals(
            "sidx and every fragment must be byte-identical and contiguous",
            media,
            fragmentRegion(after),
        )
        assertEquals(after.size.toLong(), boxes(after).sumOf { it.size })
        assertEquals("Tone", readItem(after, "©nam"))
        assertEquals(jpeg.size, Mp4Tag.coverArtSize(file))
        assertFalse(Mp4Tag.needsFragmentRelayout(file))
    }

    @Test
    fun `a fragmented file broken by the old writer is repaired by any rewrite`() {
        // Rebuild exactly what the old writer produced: moov blanked into a same-size free
        // box where it stood, and the new moov appended after everything else.
        val original = fragmentedM4a().readBytes()
        val moov = boxes(original).first { it.type == "moov" }
        val moovBytes = original.copyOfRange(moov.offset.toInt(), (moov.offset + moov.size).toInt())
        val broken = original.copyOf().also { bytes ->
            val free = box("free", ByteArray(moov.size.toInt() - 8))
            System.arraycopy(free, 0, bytes, moov.offset.toInt(), free.size)
        } + moovBytes
        val file = write(broken)
        assertTrue(Mp4Tag.needsFragmentRelayout(file))

        assertTrue(Mp4Tag.rewrite(file, null, null, null, null, null))

        val after = file.readBytes()
        assertEquals(listOf("ftyp", "moov", "sidx"), boxes(after).map { it.type }.take(3))
        assertFalse("the dead free box ahead of the fragments goes too", boxes(after).any { it.type == "free" })
        assertArrayEquals(fragmentRegion(original), fragmentRegion(after))
        assertFalse(Mp4Tag.needsFragmentRelayout(file))
    }

    @Test
    fun `a healthy fragmented file does not need repair`() {
        assertFalse(Mp4Tag.needsFragmentRelayout(fragmentedM4a()))
        assertFalse(Mp4Tag.needsFragmentRelayout(realM4a()))
    }

    /** Everything from `sidx` through the last `mdat`: the part a relayout must not alter. */
    private fun fragmentRegion(bytes: ByteArray): ByteArray {
        val parsed = boxes(bytes)
        val first = parsed.first { it.type == "sidx" }
        val last = parsed.last { it.type == "mdat" }
        return bytes.copyOfRange(first.offset.toInt(), (last.offset + last.size).toInt())
    }

    /** Three seconds of AAC from ffmpeg with `+frag_keyframe+empty_moov+default_base_moof+global_sidx`. */
    private fun fragmentedM4a(): File = write(resource("sample_fragmented.m4a"))

    /** A fresh copy each time, since every test here writes to it. */
    private fun realM4a(): File = write(resource(REAL_M4A))

    private fun resource(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/$name")) {
            "test resource $name is missing"
        }.use { it.readBytes() }

    // --- Synthetic file construction ---------------------------------------------------

    private fun write(bytes: ByteArray): File =
        temp.newFile().apply { writeBytes(bytes) }

    /**
     * A minimal but structurally honest MP4: `ftyp`, a `moov` holding an `mvhd` and a
     * `trak`, and an `mdat` of known bytes.
     */
    private fun buildMp4(
        moovFirst: Boolean,
        extraItem: Pair<String, String>? = null,
    ): ByteArray {
        val moovBody = box("mvhd", ByteArray(100)) +
            box("trak", box("tkhd", ByteArray(84))) +
            (extraItem?.let { (type, value) -> udtaWith(type, value) } ?: ByteArray(0))
        val moov = box("moov", moovBody)
        val mdat = box("mdat", audioPayload)
        val ftyp = box("ftyp", "M4A isom".toByteArray())
        return if (moovFirst) ftyp + moov + mdat else ftyp + mdat + moov
    }

    /**
     * Stand-in for a real cover image: SOI/EOI markers around a deterministic ramp.
     *
     * The ramp matters — consecutive bytes differ by one, so the four letters of an atom
     * name can never appear inside it and confuse the reader below into finding "art"
     * that is really just image data.
     */
    private fun fakeJpeg(size: Int, seed: Int = 0): ByteArray {
        val body = ByteArray(size - 4) { ((it + seed) % 251).toByte() }
        return byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
            body +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte())
    }

    private fun udtaWith(type: String, value: String): ByteArray {
        val data = box("data", byteArrayOf(0, 0, 0, 1) + ByteArray(4) + value.toByteArray())
        val ilst = box("ilst", box(type, data))
        val hdlr = box("hdlr", ByteArray(4) + ByteArray(4) + "mdirappl".toByteArray() + ByteArray(9))
        return box("udta", box("meta", byteArrayOf(0, 0, 0, 0) + hdlr + ilst))
    }

    private fun box(type: String, body: ByteArray): ByteArray {
        val size = body.size + 8
        return byteArrayOf(
            (size shr 24).toByte(), (size shr 16).toByte(),
            (size shr 8).toByte(), size.toByte(),
        ) + type.toByteArray(Charsets.ISO_8859_1) + body
    }

    // --- Reading the result back --------------------------------------------------------

    private data class ParsedBox(val type: String, val offset: Long, val size: Long)

    private fun boxes(bytes: ByteArray): List<ParsedBox> {
        val result = mutableListOf<ParsedBox>()
        var offset = 0
        while (offset + 8 <= bytes.size) {
            val size = readUInt32(bytes, offset)
            val type = String(bytes, offset + 4, 4, Charsets.ISO_8859_1)
            if (size < 8 || offset + size > bytes.size) break
            result += ParsedBox(type, offset.toLong(), size.toLong())
            offset += size
        }
        return result
    }

    private fun offsetOf(bytes: ByteArray, type: String): Long =
        boxes(bytes).first { it.type == type }.offset

    private fun payloadOf(bytes: ByteArray, type: String): ByteArray {
        val box = boxes(bytes).first { it.type == type }
        return bytes.copyOfRange(box.offset.toInt() + 8, (box.offset + box.size).toInt())
    }

    /** Finds an ilst item anywhere in the file and returns its data payload. */
    private fun readItemBytes(bytes: ByteArray, atom: String): ByteArray? {
        val needle = atom.toByteArray(Charsets.ISO_8859_1)
        // Search from the end: the live moov is the last one written.
        for (start in bytes.size - needle.size downTo 4) {
            if (!regionMatches(bytes, start, needle)) continue
            val itemSize = readUInt32(bytes, start - 4)
            if (itemSize < 16 || start - 4 + itemSize > bytes.size) continue
            // item = [size][atom][data box: size][data][version+flags][locale][payload]
            val dataStart = start + 4
            val dataSize = readUInt32(bytes, dataStart)
            if (String(bytes, dataStart + 4, 4, Charsets.ISO_8859_1) != "data") continue
            return bytes.copyOfRange(dataStart + 16, dataStart + dataSize)
        }
        return null
    }

    private fun readItem(bytes: ByteArray, atom: String): String? =
        readItemBytes(bytes, atom)?.toString(Charsets.UTF_8)

    private fun regionMatches(bytes: ByteArray, at: Int, needle: ByteArray): Boolean {
        if (at + needle.size > bytes.size) return false
        for (i in needle.indices) if (bytes[at + i] != needle[i]) return false
        return true
    }

    private fun readUInt32(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)

    @Test
    fun `sanity check on the synthetic fixture itself`() {
        // If the fixture were malformed the tests above would pass vacuously.
        val bytes = buildMp4(moovFirst = true)
        val parsed = boxes(bytes)
        assertEquals(listOf("ftyp", "moov", "mdat"), parsed.map { it.type })
        assertEquals(bytes.size.toLong(), parsed.sumOf { it.size })
        assertNull(readItem(bytes, "©nam"))
    }
}
