package com.andres.musicplayer

import com.andres.musicplayer.playback.MonoBlend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the in-app mono to AOSP's mono_blend(limit = true) + limiter(). */
class MonoBlendTest {

    private val sqrt1_2 = 0.70710678f

    @Test
    fun belowTheKneeTheLimiterIsTransparent() {
        assertEquals(0.5f, MonoBlend.limiter(0.5f), 1e-6f)
        assertEquals(-0.3f, MonoBlend.limiter(-0.3f), 1e-6f)
    }

    @Test
    fun limiterIsContinuousAtTheKneeAndTopsOutAtOne() {
        // AOSP's spline meets the straight line at 1/√2 and reaches exactly 1.0 at √2.
        assertEquals(sqrt1_2, MonoBlend.limiter(sqrt1_2 + 1e-5f), 1e-4f)
        assertEquals(1f, MonoBlend.limiter(1.41421356f), 1e-4f)
        assertEquals(1f, MonoBlend.limiter(3f), 0f)
        assertEquals(-1f, MonoBlend.limiter(-3f), 0f)
    }

    @Test
    fun limiterNeverExceedsFullScaleAndIsMonotonic() {
        var last = -1f
        var x = 0f
        while (x <= 2f) {
            val y = MonoBlend.limiter(x)
            assertTrue(y <= 1f)
            assertTrue(y >= last)
            last = y
            x += 0.001f
        }
    }

    @Test
    fun blendIsSumTimesRootHalfNotAnAverage() {
        // A centred quiet signal comes out ~3 dB louder than a plain average would make it.
        assertEquals(0.2f * 2 * sqrt1_2, MonoBlend.blend(0.2f, 0.2f), 1e-6f)
        // One side only: also 1/√2 of it, in both ears.
        assertEquals(0.4f * sqrt1_2, MonoBlend.blend(0.4f, 0f), 1e-6f)
        // Opposite phase cancels, as with any mono downmix.
        assertEquals(0f, MonoBlend.blend(0.6f, -0.6f), 1e-6f)
        // Full-scale on both sides is limited to full scale, not clipped past it.
        assertEquals(1f, MonoBlend.blend(1f, 1f), 1e-4f)
    }
}
