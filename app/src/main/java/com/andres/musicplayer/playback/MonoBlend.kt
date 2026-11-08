package com.andres.musicplayer.playback

import kotlin.math.abs

/**
 * Android's own stereo-to-mono maths, so the app's mono sounds like the system setting.
 *
 * Android does this in AudioFlinger (`mono_blend()` in system/media/audio_utils, called
 * with `limit = true` on its float mix): left + right, scaled by 1/√2, then passed
 * through `limiter()` — a soft knee that leaves anything below 1/√2 untouched and
 * smoothly rounds louder peaks off so they never clip. That is ~3 dB louder than a plain
 * average. [limiter] is a line-for-line port of AOSP's `audio_utils/limiter.c` (the
 * polynomial-spline variant it compiles).
 *
 * Applied last in [InAppEffectsProcessor], after every effect — where Android applies it.
 */
object MonoBlend {
    private const val SQRT1_2 = 0.70710678f
    private const val SQRT2 = 1.41421356f

    // Cubic spline coefficients from AOSP audio_utils/limiter.c.
    private const val A = 0.3431457505f
    private const val B = -1.798989873f
    private const val C3 = 3.029437252f
    private const val D = -0.6568542495f

    /** AOSP `mono_blend` for float stereo with `limit = true`. */
    fun blend(left: Float, right: Float): Float = limiter((left + right) * SQRT1_2)

    /** Port of AOSP `limiter()`: linear up to 1/√2, a smooth cubic knee to 1.0 at √2. */
    fun limiter(input: Float): Float {
        val inAbs = abs(input)
        if (inAbs <= SQRT1_2) return input
        val out = if (inAbs < SQRT2) ((A * inAbs + B) * inAbs + C3) * inAbs + D else 1f
        return if (input < 0) -out else out
    }
}
