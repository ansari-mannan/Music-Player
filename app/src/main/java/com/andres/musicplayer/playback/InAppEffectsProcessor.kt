package com.andres.musicplayer.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tanh

/**
 * The app's own sound effects, run inside the player so they behave identically on every
 * phone. Android's built-in equalizer / bass boost / virtualizer / reverb come from the
 * phone maker's audio library, and on some phones those are broken — on the Smart 10 they
 * silenced playback or did nothing at all.
 *
 * Settings mirror Android's stock ("NXP bundle") effects, taken from AOSP's
 * frameworks/av/media/libeffects:
 * - Equalizer: 5 peaking bands at 60 / 230 / 910 / 3600 / 14000 Hz, Q 0.96, ±15 dB, with the
 *   bundle's preset table ([EQ_PRESETS]).
 * - Bass boost: up to 15 dB centred on 90 Hz (strength 0–1000 → 0–15 dB, as the bundle
 *   maps it), with a high-pass so the boost doesn't pump inaudible sub-bass.
 * - Reverb: Android's seven presets, mapped from their decay time, HF ratio, level and
 *   pre-delay ([REVERB_PRESETS]) onto a Freeverb-style network. NXP's reverb algorithm
 *   itself is proprietary, so this is the same character rather than the same samples.
 * - Virtualizer: mid/side widening. NXP's "Concert Sound" is likewise a recreation.
 *
 * Mono runs last, after every effect — the same place Android's own mono sits — using
 * AOSP's exact maths ([MonoBlend]).
 *
 * - Volume leveller and loudness boost: a compressor and a gain-with-limiter, done here
 *   too. Android's versions work, but they attach to an audio *session*, and the player
 *   doesn't reliably report which session it really plays on — so they kept ending up on
 *   a silent one. In here there's no session to get wrong.
 *
 * Order: EQ → bass → virtualizer → reverb → leveller → loudness → mono → soft clip.
 */
@UnstableApi
class InAppEffectsProcessor : BaseAudioProcessor() {

    private var sampleRate = 44_100
    private var appliedVersion = -1
    private var settings = DspState.settings

    private val eqL = Array(5) { Biquad() }
    private val eqR = Array(5) { Biquad() }
    private val bassL = Biquad()
    private val bassR = Biquad()
    private val bassHpL = Biquad()
    private val bassHpR = Biquad()
    private var reverb: Freeverb? = null
    private val leveller = Leveller()
    private val loudness = PeakLimitedGain()

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        val supported = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT ||
            inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        if (!supported || inputAudioFormat.channelCount != 2) return AudioFormat.NOT_SET
        sampleRate = inputAudioFormat.sampleRate
        appliedVersion = -1 // recompute filters for this rate
        reverb = Freeverb(sampleRate)
        leveller.configure(sampleRate)
        loudness.configure(sampleRate)
        return inputAudioFormat
    }

    override fun onFlush(streamMetadata: androidx.media3.common.audio.AudioProcessor.StreamMetadata) {
        // A seek: drop filter and reverb memory so nothing from before rings on.
        (eqL + eqR + arrayOf(bassL, bassR, bassHpL, bassHpR)).forEach { it.reset() }
        reverb?.clear()
        leveller.reset()
        loudness.reset()
    }

    private fun refresh() {
        val version = DspState.version
        if (version == appliedVersion) return
        appliedVersion = version
        settings = DspState.settings
        val fs = sampleRate.toDouble()
        for (i in 0 until 5) {
            val gain = settings.eqGainsDb.getOrElse(i) { 0f }.toDouble()
            eqL[i].peaking(fs, EQ_FREQUENCIES[i], EQ_Q, gain)
            eqR[i].peaking(fs, EQ_FREQUENCIES[i], EQ_Q, gain)
        }
        val bassDb = 15.0 * settings.bassStrength / 1000.0
        bassL.peaking(fs, BASS_CENTRE_HZ, BASS_Q, bassDb)
        bassR.peaking(fs, BASS_CENTRE_HZ, BASS_Q, bassDb)
        bassHpL.highPass(fs, BASS_HPF_HZ, 0.707)
        bassHpR.highPass(fs, BASS_HPF_HZ, 0.707)
        reverb?.setPreset(settings.reverbPreset)
        loudness.setGainMb(settings.loudnessGainMb)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        refresh()
        val s = settings
        val mono = PlaybackPrefs.monoEnabled
        val eqOn = s.eqEnabled && s.eqGainsDb.any { it != 0f }
        val bassOn = s.bassEnabled && s.bassStrength > 0
        val virtOn = s.virtEnabled && s.virtStrength > 0
        val reverbOn = s.reverbPreset != 0
        val levellerOn = s.levellerEnabled
        val loudnessOn = s.loudnessEnabled && s.loudnessGainMb > 0
        val anyEffect = eqOn || bassOn || virtOn || reverbOn || levellerOn || loudnessOn

        val out = replaceOutputBuffer(remaining)
        if (!anyEffect && !mono) {
            out.put(inputBuffer)
            out.flip()
            return
        }

        val isFloat = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val frameBytes = if (isFloat) 8 else 4
        val width = 1.2f * s.virtStrength / 1000f
        val rev = reverb

        while (inputBuffer.remaining() >= frameBytes) {
            var l: Float
            var r: Float
            if (isFloat) {
                l = inputBuffer.float
                r = inputBuffer.float
            } else {
                l = inputBuffer.short / 32768f
                r = inputBuffer.short / 32768f
            }

            if (eqOn) {
                for (i in 0 until 5) {
                    l = eqL[i].process(l)
                    r = eqR[i].process(r)
                }
            }
            if (bassOn) {
                l = bassL.process(bassHpL.process(l))
                r = bassR.process(bassHpR.process(r))
            }
            if (virtOn) {
                // Mid/side: widen the side, and trim the overall level so wider isn't louder.
                val mid = (l + r) * 0.5f
                val side = (l - r) * 0.5f * (1f + width)
                val trim = 1f / (1f + width * 0.35f)
                l = (mid + side) * trim
                r = (mid - side) * trim
            }
            if (reverbOn && rev != null) {
                rev.process(l, r)
                l += rev.outL
                r += rev.outR
            }
            if (levellerOn) {
                val g = leveller.gainFor(l, r)
                l *= g
                r *= g
            }
            if (loudnessOn) {
                val g = loudness.gainFor(l, r)
                l *= g
                r *= g
            }
            if (mono) {
                val m = MonoBlend.blend(l, r)
                l = m
                r = m
            } else if (anyEffect) {
                l = softClip(l)
                r = softClip(r)
            }

            if (isFloat) {
                out.putFloat(l)
                out.putFloat(r)
            } else {
                out.putShort((l * 32768f).roundToInt().coerceIn(-32768, 32767).toShort())
                out.putShort((r * 32768f).roundToInt().coerceIn(-32768, 32767).toShort())
            }
        }
        while (inputBuffer.hasRemaining()) out.put(inputBuffer.get())
        out.flip()
    }

    /** Transparent below -1 dBFS, then rounds peaks off smoothly instead of clipping hard. */
    private fun softClip(x: Float): Float {
        val a = abs(x)
        if (a <= CLIP_KNEE) return x
        val shaped = CLIP_KNEE + (1f - CLIP_KNEE) * tanh((a - CLIP_KNEE) / (1f - CLIP_KNEE))
        return if (x < 0) -shaped else shaped
    }

    companion object {
        private const val CLIP_KNEE = 0.891f // -1 dBFS

        /** AOSP EQNB_5BandPresetsFrequencies. */
        val EQ_FREQUENCIES = doubleArrayOf(60.0, 230.0, 910.0, 3600.0, 14000.0)

        /** AOSP EQNB_5BandPresetsQFactors (96 / 100). */
        const val EQ_Q = 0.96

        /** AOSP: Equalizer band level range is -1500..1500 mB. */
        const val EQ_MAX_DB = 15

        /** AOSP gEqualizerPresets names, with EQNB_5BandSoftPresets gains (dB). */
        val EQ_PRESETS: List<Pair<String, IntArray>> = listOf(
            "Normal" to intArrayOf(3, 0, 0, 0, 3),
            "Classical" to intArrayOf(5, 3, -2, 4, 4),
            "Dance" to intArrayOf(6, 0, 2, 4, 1),
            "Flat" to intArrayOf(0, 0, 0, 0, 0),
            "Folk" to intArrayOf(3, 0, 0, 2, -1),
            "Heavy Metal" to intArrayOf(4, 1, 9, 3, 0),
            "Hip Hop" to intArrayOf(5, 3, 0, 1, 3),
            "Jazz" to intArrayOf(4, 2, -2, 2, 5),
            "Pop" to intArrayOf(-1, 2, 5, 1, -2),
            "Rock" to intArrayOf(5, 3, -1, 3, 5),
        )

        /** AOSP BassSetStrength: LVM_BE_CENTRE_90Hz, HPF on. */
        private const val BASS_CENTRE_HZ = 90.0
        private const val BASS_Q = 0.8
        private const val BASS_HPF_HZ = 30.0

        /**
         * AOSP sReverbPresets, as (name, decayTime ms, decayHFRatio ‰, reverbLevel mB,
         * reverbDelay ms). Index matches PresetReverb.PRESET_*.
         */
        val REVERB_PRESETS: List<ReverbPreset> = listOf(
            ReverbPreset("Off", 0, 0, 0, 0),
            ReverbPreset("Small room", 1100, 830, 500, 10),
            ReverbPreset("Medium room", 1300, 830, -200, 20),
            ReverbPreset("Large room", 1500, 830, -1000, 40),
            ReverbPreset("Medium hall", 1800, 700, -800, 30),
            ReverbPreset("Large hall", 1800, 700, -1400, 60),
            ReverbPreset("Plate", 1300, 900, 0, 10),
        )
    }
}

class ReverbPreset(val name: String, val decayMs: Int, val hfRatio: Int, val levelMb: Int, val delayMs: Int)

/** What the in-app effects should do right now. Written by [AudioEffects], read on the audio thread. */
data class DspSettings(
    val eqEnabled: Boolean = false,
    val eqGainsDb: List<Float> = List(5) { 0f },
    val bassEnabled: Boolean = false,
    val bassStrength: Int = 0,
    val virtEnabled: Boolean = false,
    val virtStrength: Int = 0,
    val reverbPreset: Int = 0,
    val levellerEnabled: Boolean = false,
    val loudnessEnabled: Boolean = false,
    /** Target boost in millibels, 0–1500 (as Android's LoudnessEnhancer takes it). */
    val loudnessGainMb: Int = 0,
)

object DspState {
    @Volatile
    var settings: DspSettings = DspSettings()
        private set

    @Volatile
    var version: Int = 0
        private set

    fun update(new: DspSettings) {
        settings = new
        version++
    }
}

/** One second-order filter (RBJ cookbook), transposed direct form II. */
private class Biquad {
    private var b0 = 1f
    private var b1 = 0f
    private var b2 = 0f
    private var a1 = 0f
    private var a2 = 0f
    private var z1 = 0f
    private var z2 = 0f

    fun peaking(fs: Double, freq: Double, q: Double, gainDb: Double) {
        val f = freq.coerceAtMost(fs * 0.45)
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2 * PI * f / fs
        val alpha = sin(w0) / (2 * q)
        val cosW = cos(w0)
        val a0 = 1 + alpha / a
        set((1 + alpha * a) / a0, (-2 * cosW) / a0, (1 - alpha * a) / a0, (-2 * cosW) / a0, (1 - alpha / a) / a0)
    }

    fun highPass(fs: Double, freq: Double, q: Double) {
        val w0 = 2 * PI * freq.coerceAtMost(fs * 0.45) / fs
        val alpha = sin(w0) / (2 * q)
        val cosW = cos(w0)
        val a0 = 1 + alpha
        set((1 + cosW) / 2 / a0, -(1 + cosW) / a0, (1 + cosW) / 2 / a0, (-2 * cosW) / a0, (1 - alpha) / a0)
    }

    private fun set(nb0: Double, nb1: Double, nb2: Double, na1: Double, na2: Double) {
        b0 = nb0.toFloat()
        b1 = nb1.toFloat()
        b2 = nb2.toFloat()
        a1 = na1.toFloat()
        a2 = na2.toFloat()
    }

    fun process(x: Float): Float {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    fun reset() {
        z1 = 0f
        z2 = 0f
    }
}

/**
 * Freeverb (Jezar at Dreampoint, public domain): 8 damped comb filters into 4 all-passes
 * per channel, with a stereo spread. Configured from Android's preset parameters.
 */
private class Freeverb(sampleRate: Int) {
    private val scale = sampleRate / 44_100.0
    private val combsL = COMB_TUNING.map { Comb((it * scale).toInt().coerceAtLeast(1)) }
    private val combsR = COMB_TUNING.map { Comb(((it + STEREO_SPREAD) * scale).toInt().coerceAtLeast(1)) }
    private val allpassL = ALLPASS_TUNING.map { Allpass((it * scale).toInt().coerceAtLeast(1)) }
    private val allpassR = ALLPASS_TUNING.map { Allpass(((it + STEREO_SPREAD) * scale).toInt().coerceAtLeast(1)) }
    private val preDelay = FloatArray((sampleRate * 0.1).toInt().coerceAtLeast(1))
    private val sampleRate = sampleRate
    private var preDelayLength = 1
    private var preDelayPos = 0
    private var wet = 0f

    var outL = 0f
        private set
    var outR = 0f
        private set

    fun setPreset(index: Int) {
        val preset = InAppEffectsProcessor.REVERB_PRESETS.getOrNull(index) ?: return
        if (index == 0) {
            wet = 0f
            return
        }
        // Comb feedback straight from Android's decay time: a comb of delay d decays by
        // 60 dB in T seconds when g = 10^(-3d/T). Uses the combs' average delay.
        val avgDelaySec = COMB_TUNING.average() / 44_100.0
        val feedback = 10.0.pow(-3.0 * avgDelaySec / (preset.decayMs / 1000.0)).toFloat()
        // A lower HF ratio means highs die away faster → more damping in the loop.
        val damp = ((1000 - preset.hfRatio) / 1000.0 * 1.5).coerceIn(0.0, 0.9)
        (combsL + combsR).forEach { it.set(feedback, (damp * SCALE_DAMP).toFloat()) }
        // Output level, shaped by Android's reverbLevel (millibels). Measured so the tail
        // sits roughly 6-10 dB under the music, where it's clearly heard but doesn't wash
        // the song out; the earlier setting left it ~17 dB under, masked by the music.
        wet = (WET_SCALE * 10.0.pow(preset.levelMb / 6000.0)).toFloat()
        preDelayLength = (sampleRate * preset.delayMs / 1000).coerceIn(1, preDelay.size)
    }

    fun process(l: Float, r: Float) {
        if (wet == 0f) {
            outL = 0f
            outR = 0f
            return
        }
        // Pre-delay the (mono) send, as Android's reverb delays its tail.
        val input = (l + r) * FIXED_GAIN
        preDelay[preDelayPos] = input
        val readPos = (preDelayPos - preDelayLength + preDelay.size) % preDelay.size
        val delayed = preDelay[readPos]
        preDelayPos = (preDelayPos + 1) % preDelay.size

        var accL = 0f
        var accR = 0f
        for (i in combsL.indices) {
            accL += combsL[i].process(delayed)
            accR += combsR[i].process(delayed)
        }
        for (i in allpassL.indices) {
            accL = allpassL[i].process(accL)
            accR = allpassR[i].process(accR)
        }
        outL = accL * wet
        outR = accR * wet
    }

    fun clear() {
        (combsL + combsR).forEach { it.clear() }
        (allpassL + allpassR).forEach { it.clear() }
        preDelay.fill(0f)
    }

    private class Comb(size: Int) {
        private val buffer = FloatArray(size)
        private var index = 0
        private var store = 0f
        private var feedback = 0.5f
        private var damp = 0.2f

        fun set(feedback: Float, damp: Float) {
            this.feedback = feedback
            this.damp = damp
        }

        fun process(input: Float): Float {
            val output = buffer[index]
            store = output * (1 - damp) + store * damp
            buffer[index] = input + store * feedback
            index = (index + 1) % buffer.size
            return output
        }

        fun clear() {
            buffer.fill(0f)
            store = 0f
        }
    }

    private class Allpass(size: Int) {
        private val buffer = FloatArray(size)
        private var index = 0

        fun process(input: Float): Float {
            val buffered = buffer[index]
            val output = -input + buffered
            buffer[index] = input + buffered * 0.5f
            index = (index + 1) % buffer.size
            return output
        }

        fun clear() = buffer.fill(0f)
    }

    private companion object {
        val COMB_TUNING = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
        val ALLPASS_TUNING = intArrayOf(556, 441, 341, 225)
        const val STEREO_SPREAD = 23
        const val FIXED_GAIN = 0.015f
        const val SCALE_DAMP = 0.4
        const val WET_SCALE = 2.4
    }
}

/**
 * Volume leveller: a feed-forward compressor (threshold -28 dB, ratio 3:1, 5 ms attack,
 * 250 ms release, +8 dB make-up) — the same settings the app gave Android's
 * DynamicsProcessing — followed by the peak limiter so the make-up gain never clips.
 */
private class Leveller {
    private var envelope = 0f
    private var attack = 0f
    private var release = 0f
    private val limiter = PeakLimitedGain()

    fun configure(sampleRate: Int) {
        attack = coefficient(sampleRate, 5.0)
        release = coefficient(sampleRate, 250.0)
        limiter.configure(sampleRate)
        limiter.setGainMb(0)
        reset()
    }

    fun reset() {
        envelope = 0f
        limiter.reset()
    }

    /** Gain to apply to this frame. */
    fun gainFor(l: Float, r: Float): Float {
        val level = maxOf(abs(l), abs(r))
        val coef = if (level > envelope) attack else release
        envelope = level + coef * (envelope - level)
        val levelDb = 20f * kotlin.math.log10(envelope.coerceAtLeast(1e-6f))
        val over = levelDb - THRESHOLD_DB
        val reductionDb = if (over > 0f) over - over / RATIO else 0f
        val gain = 10f.pow((MAKEUP_DB - reductionDb) / 20f)
        // Keep the made-up level from clipping.
        return gain * limiter.gainFor(l * gain, r * gain)
    }

    private companion object {
        const val THRESHOLD_DB = -28f
        const val RATIO = 3f
        const val MAKEUP_DB = 8f
    }
}

/**
 * A fixed boost with a peak limiter on top, like Android's LoudnessEnhancer: quiet audio
 * comes up by the full amount, and peaks that would clip are turned down just enough —
 * instantly on the way up, easing back over ~100 ms.
 */
private class PeakLimitedGain {
    private var target = 1f
    private var envelope = 0f
    private var release = 0f

    fun configure(sampleRate: Int) {
        release = coefficient(sampleRate, 100.0)
        reset()
    }

    fun setGainMb(mb: Int) {
        target = 10f.pow(mb / 2000f)
    }

    fun reset() {
        envelope = 0f
    }

    fun gainFor(l: Float, r: Float): Float {
        val peak = maxOf(abs(l), abs(r)) * target
        envelope = if (peak > envelope) peak else peak + release * (envelope - peak)
        return if (envelope > CEILING) target * CEILING / envelope else target
    }

    private companion object {
        const val CEILING = 0.95f
    }
}

/** One-pole smoothing coefficient for a time constant of [ms] at [sampleRate]. */
private fun coefficient(sampleRate: Int, ms: Double): Float =
    kotlin.math.exp(-1.0 / (sampleRate * ms / 1000.0)).toFloat()

