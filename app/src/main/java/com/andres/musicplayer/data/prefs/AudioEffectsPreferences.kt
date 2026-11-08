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

    /** Equalizer preset last chosen, or -1 for a custom curve (bands moved by hand). */
    var eqPreset: Int
        get() = prefs.getInt(KEY_EQ_PRESET, -1)
        set(value) = prefs.edit().putInt(KEY_EQ_PRESET, value).apply()

    /** PresetReverb preset (PresetReverb.PRESET_*); 0 = none. */
    var reverbPreset: Int
        get() = prefs.getInt(KEY_REVERB_PRESET, 0)
        set(value) = prefs.edit().putInt(KEY_REVERB_PRESET, value).apply()

    var loudnessEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOUDNESS_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_LOUDNESS_ENABLED, value).apply()

    /** Loudness enhancer target gain in millibels (0–1500). */
    var loudnessGainMb: Int
        get() = prefs.getInt(KEY_LOUDNESS_GAIN, 600)
        set(value) = prefs.edit().putInt(KEY_LOUDNESS_GAIN, value).apply()

    /** Volume leveller (compressor + limiter via DynamicsProcessing). */
    var levelerEnabled: Boolean
        get() = prefs.getBoolean(KEY_LEVELER_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_LEVELER_ENABLED, value).apply()

    companion object {
        private const val PREFS_NAME = "audio_effects_prefs"
        private const val KEY_EQ_PRESET = "eq_preset"
        private const val KEY_REVERB_PRESET = "reverb_preset"
        private const val KEY_LOUDNESS_ENABLED = "loudness_enabled"
        private const val KEY_LOUDNESS_GAIN = "loudness_gain_mb"
        private const val KEY_LEVELER_ENABLED = "leveler_enabled"
        private const val KEY_EQ_ENABLED = "eq_enabled"
        private const val KEY_EQ_BANDS = "eq_bands"
        private const val KEY_BASS_ENABLED = "bass_enabled"
        private const val KEY_BASS_STRENGTH = "bass_strength"
        private const val KEY_VIRT_ENABLED = "virt_enabled"
        private const val KEY_VIRT_STRENGTH = "virt_strength"
    }
}
