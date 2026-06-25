package com.andres.musicplayer.data.prefs

import android.content.Context

/**
 * Persists the audio-effect settings (equalizer, bass boost, virtualizer) so they
 * survive process death and are re-applied to a fresh audio session every time.
 *
 * The equalizer is stored as its per-band gain levels (millibels, comma-joined),
 * which captures both custom curves and applied presets uniformly — a preset just
 * sets band levels, and we save whatever the bands ended up at.
 */
class AudioEffectsPreferences(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var eqEnabled: Boolean
        get() = prefs.getBoolean(KEY_EQ_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_EQ_ENABLED, value).apply()

    /** Per-band gain levels in millibels. Empty when nothing has been saved yet. */
    var eqBands: List<Short>
        get() = prefs.getString(KEY_EQ_BANDS, null)
            ?.split(",")
            ?.mapNotNull { it.trim().toShortOrNull() }
            ?: emptyList()
        set(value) = prefs.edit().putString(KEY_EQ_BANDS, value.joinToString(",")).apply()

    var bassEnabled: Boolean
        get() = prefs.getBoolean(KEY_BASS_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_BASS_ENABLED, value).apply()

    /** Bass boost strength 0–1000. */
    var bassStrength: Short
        get() = prefs.getInt(KEY_BASS_STRENGTH, 0).toShort()
        set(value) = prefs.edit().putInt(KEY_BASS_STRENGTH, value.toInt()).apply()

    var virtualizerEnabled: Boolean
        get() = prefs.getBoolean(KEY_VIRT_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_VIRT_ENABLED, value).apply()

    /** Virtualizer strength 0–1000. */
    var virtualizerStrength: Short
        get() = prefs.getInt(KEY_VIRT_STRENGTH, 0).toShort()
        set(value) = prefs.edit().putInt(KEY_VIRT_STRENGTH, value.toInt()).apply()

    companion object {
        private const val PREFS_NAME = "audio_effects_prefs"
        private const val KEY_EQ_ENABLED = "eq_enabled"
        private const val KEY_EQ_BANDS = "eq_bands"
        private const val KEY_BASS_ENABLED = "bass_enabled"
        private const val KEY_BASS_STRENGTH = "bass_strength"
        private const val KEY_VIRT_ENABLED = "virt_enabled"
        private const val KEY_VIRT_STRENGTH = "virt_strength"
    }
}
