package com.andres.musicplayer.playback

import android.content.Context
import com.andres.musicplayer.data.prefs.AudioEffectsPreferences

/**
 * The single place the UI changes sound settings. Every setter applies the change AND
 * saves it, so "applied now" and "saved for next time" can never drift apart.
 *
 * Every effect — equalizer, bass boost, virtualizer, reverb, volume leveller, loudness
 * boost — is computed by [InAppEffectsProcessor] inside the player. Nothing is attached
 * to an Android audio session any more: the phone maker's versions of the classic effects
 * were broken on some phones, and the player doesn't reliably report which session it
 * really plays on, so session-attached effects could end up processing silence.
 */
object AudioEffects {

    private var prefs: AudioEffectsPreferences? = null

    /** Call once at startup (App.onCreate), before playback starts. */
    fun init(context: Context) {
        if (prefs != null) return
        prefs = AudioEffectsPreferences(context.applicationContext)
        pushDsp()
    }

    /** Sends the in-app effect settings to the audio thread. */
    private fun pushDsp() {
        val p = prefs ?: return
        DspState.update(
            DspSettings(
                eqEnabled = p.eqEnabled,
                eqGainsDb = eqGainsDb,
                bassEnabled = p.bassEnabled,
                bassStrength = p.bassStrength.toInt(),
                virtEnabled = p.virtualizerEnabled,
                virtStrength = p.virtualizerStrength.toInt(),
                reverbPreset = p.reverbPreset,
                levellerEnabled = p.levelerEnabled,
                loudnessEnabled = p.loudnessEnabled,
                loudnessGainMb = p.loudnessGainMb,
            ),
        )
    }

    // --- Equalizer (in-app) ---

    val eqBandFrequencies: DoubleArray get() = InAppEffectsProcessor.EQ_FREQUENCIES
    val eqMaxDb: Int get() = InAppEffectsProcessor.EQ_MAX_DB
    val eqPresetNames: List<String> = InAppEffectsProcessor.EQ_PRESETS.map { it.first }

    val eqEnabled: Boolean get() = prefs?.eqEnabled == true

    /** Five band gains in dB. Saved as millibels, as before, so old settings carry over. */
    val eqGainsDb: List<Float>
        get() = prefs?.eqBands?.takeIf { it.size == 5 }?.map { it / 100f } ?: List(5) { 0f }

    /** The preset last applied, or -1 for a custom curve. */
    val eqPreset: Int get() = prefs?.eqPreset ?: -1

    /** Turning the equalizer off also sets it back to flat. */
    fun setEqEnabled(value: Boolean) {
        prefs?.eqEnabled = value
        if (!value) {
            prefs?.eqBands = List(5) { 0.toShort() }
            prefs?.eqPreset = FLAT_PRESET
        }
        pushDsp()
    }

    /** Index of "Flat" in [eqPresetNames]. */
    val FLAT_PRESET: Int = InAppEffectsProcessor.EQ_PRESETS.indexOfFirst { it.first == "Flat" }

    fun setEqBandDb(band: Int, db: Float) {
        val gains = eqGainsDb.toMutableList()
        if (band !in gains.indices) return
        gains[band] = db.coerceIn(-eqMaxDb.toFloat(), eqMaxDb.toFloat())
        prefs?.eqBands = gains.map { (it * 100).toInt().toShort() }
        prefs?.eqPreset = -1 // moved by hand: now a custom curve
        prefs?.eqEnabled = true // moving a slider switches the equalizer on
        pushDsp()
    }

    fun applyEqPreset(index: Int) {
        val gains = InAppEffectsProcessor.EQ_PRESETS.getOrNull(index)?.second ?: return
        prefs?.eqBands = gains.map { (it * 100).toShort() }
        prefs?.eqPreset = index
        prefs?.eqEnabled = true
        pushDsp()
    }

    // --- Bass boost (in-app) ---

    val bassEnabled: Boolean get() = prefs?.bassEnabled == true
    val bassStrength: Short get() = prefs?.bassStrength ?: 0

    /** Turning it off also brings the strength back to zero. */
    fun setBassEnabled(value: Boolean) {
        prefs?.bassEnabled = value
        if (!value) prefs?.bassStrength = 0
        pushDsp()
    }

    fun setBassStrength(value: Short) {
        prefs?.bassStrength = value
        pushDsp()
    }

    // --- Virtualizer (in-app) ---

    val virtualizerEnabled: Boolean get() = prefs?.virtualizerEnabled == true
    val virtualizerStrength: Short get() = prefs?.virtualizerStrength ?: 0

    /** Turning it off also brings the strength back to zero. */
    fun setVirtualizerEnabled(value: Boolean) {
        prefs?.virtualizerEnabled = value
        if (!value) prefs?.virtualizerStrength = 0
        pushDsp()
    }

    fun setVirtualizerStrength(value: Short) {
        prefs?.virtualizerStrength = value
        pushDsp()
    }

    // --- Reverb (in-app) ---

    /** Android's reverb presets (index = PresetReverb.PRESET_*), names as shown. */
    val reverbPresetNames: List<String> = InAppEffectsProcessor.REVERB_PRESETS.map { it.name }

    val reverbPreset: Int get() = prefs?.reverbPreset ?: 0

    fun setReverbPreset(preset: Int) {
        prefs?.reverbPreset = preset
        pushDsp()
    }

    // --- Loudness boost (in-app) ---

    val loudnessEnabled: Boolean get() = prefs?.loudnessEnabled == true
    val loudnessGainMb: Int get() = prefs?.loudnessGainMb ?: 600

    /** Turning it off also brings the boost back to zero. */
    fun setLoudnessEnabled(value: Boolean) {
        prefs?.loudnessEnabled = value
        if (!value) prefs?.loudnessGainMb = 0
        pushDsp()
    }

    fun setLoudnessGain(mb: Int) {
        prefs?.loudnessGainMb = mb
        pushDsp()
    }

    // --- Volume leveller (in-app) ---

    val levelerEnabled: Boolean get() = prefs?.levelerEnabled == true

    fun setLevelerEnabled(value: Boolean) {
        prefs?.levelerEnabled = value
        pushDsp()
    }
}
