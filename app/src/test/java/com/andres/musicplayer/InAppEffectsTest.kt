package com.andres.musicplayer

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.andres.musicplayer.playback.DspSettings
import com.andres.musicplayer.playback.DspState
import com.andres.musicplayer.playback.InAppEffectsProcessor
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class InAppEffectsTest {

    private val rate = 44_100

    @After
    fun reset() = DspState.update(DspSettings())

    /** Stereo 16-bit sine, [seconds] long, at [hz], left and right identical unless [rightHz]. */
    private fun sine(hz: Double, seconds: Double, amp: Double = 0.3, rightHz: Double = hz): ShortArray {
        val frames = (rate * seconds).toInt()
        return ShortArray(frames * 2) { i ->
            val frame = i / 2
            val f = if (i % 2 == 0) hz else rightHz
            (sin(2 * PI * f * frame / rate) * amp * 32767).toInt().toShort()
        }
    }

    private fun run(input: ShortArray): ShortArray {
        val processor = InAppEffectsProcessor()
        processor.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val buffer = ByteBuffer.allocateDirect(input.size * 2).order(ByteOrder.nativeOrder())
        input.forEach { buffer.putShort(it) }
        buffer.flip()
        processor.queueInput(buffer)
        val out = processor.output
        return ShortArray(out.remaining() / 2) { out.short }
    }

    private fun rms(samples: ShortArray, from: Int = 0): Double {
        var sum = 0.0
        for (i in from until samples.size) sum += samples[i].toDouble() * samples[i]
        return sqrt(sum / (samples.size - from).coerceAtLeast(1))
    }

    @Test
    fun everythingOffIsBitForBitPassthrough() {
        val input = sine(440.0, 0.1)
        assertArrayEquals(input, run(input))
    }

    @Test
    fun presetsMatchAndroidsTable() {
        val jazz = InAppEffectsProcessor.EQ_PRESETS.first { it.first == "Jazz" }.second
        assertArrayEquals(intArrayOf(4, 2, -2, 2, 5), jazz)
        assertEquals(10, InAppEffectsProcessor.EQ_PRESETS.size)
        assertArrayEquals(doubleArrayOf(60.0, 230.0, 910.0, 3600.0, 14000.0), InAppEffectsProcessor.EQ_FREQUENCIES, 0.0)
        assertEquals(7, InAppEffectsProcessor.REVERB_PRESETS.size)
    }

    @Test
    fun eqBoostRaisesItsBandAndCutLowersIt() {
        val input = sine(60.0, 1.0, amp = 0.1)
        DspState.update(DspSettings(eqEnabled = true, eqGainsDb = listOf(12f, 0f, 0f, 0f, 0f)))
        val boosted = run(input)
        DspState.update(DspSettings(eqEnabled = true, eqGainsDb = listOf(-12f, 0f, 0f, 0f, 0f)))
        val cut = run(input)
        val skip = rate // half a second in (stereo samples), past the filters' settling time
        assertTrue(rms(boosted, skip) > rms(input, skip) * 2.5) // ~+12 dB ≈ ×4
        assertTrue(rms(cut, skip) < rms(input, skip) / 2.5)
    }

    @Test
    fun bassBoostLeavesTrebleAlone() {
        val treble = sine(5000.0, 0.3, amp = 0.1)
        DspState.update(DspSettings(bassEnabled = true, bassStrength = 1000))
        val out = run(treble)
        val ratio = rms(out, 2000) / rms(treble, 2000)
        assertTrue("treble changed by $ratio", ratio in 0.9..1.1)
    }

    @Test
    fun reverbLeavesATailAfterTheSoundStops() {
        val burst = sine(440.0, 0.2) + ShortArray(rate) // 0.2 s tone, then 1 s of silence
        DspState.update(DspSettings(reverbPreset = 5)) // Large hall
        val out = run(burst)
        val tailStart = (rate * 0.4).toInt() * 2
        assertTrue("no reverb tail", rms(out, tailStart) > 5.0)
    }

    @Test
    fun virtualizerWidensStereo() {
        // Different content per side, so there's a side signal to widen.
        val input = sine(440.0, 0.2, rightHz = 660.0)
        DspState.update(DspSettings(virtEnabled = true, virtStrength = 1000))
        val out = run(input)
        fun sideEnergy(s: ShortArray): Double {
            var sum = 0.0
            for (i in s.indices step 2) {
                val side = (s[i] - s[i + 1]) / 2.0
                sum += side * side
            }
            return sum
        }
        assertTrue(sideEnergy(out) > sideEnergy(input) * 1.2)
    }

    @Test
    fun loudnessBoostRaisesQuietAudioButNeverClips() {
        val quiet = sine(440.0, 0.3, amp = 0.05)
        DspState.update(DspSettings(loudnessEnabled = true, loudnessGainMb = 1200))
        val out = run(quiet)
        assertTrue(rms(out, 2000) > rms(quiet, 2000) * 3) // +12 dB ≈ ×4
        val loud = sine(440.0, 0.3, amp = 0.9)
        val limited = run(loud)
        assertTrue(limited.all { kotlin.math.abs(it.toInt()) <= 32767 * 0.96 })
    }

    @Test
    fun levellerBringsQuietAndLoudCloserTogether() {
        DspState.update(DspSettings(levellerEnabled = true))
        val quietIn = sine(440.0, 1.0, amp = 0.02)
        val loudIn = sine(440.0, 1.0, amp = 0.6)
        val before = rms(loudIn, rate) / rms(quietIn, rate)
        val after = rms(run(loudIn), rate) / rms(run(quietIn), rate)
        assertTrue("gap $before -> $after", after < before / 2)
    }
}
