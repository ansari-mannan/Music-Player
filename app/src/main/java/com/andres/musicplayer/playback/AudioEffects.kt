package com.andres.musicplayer.playback

import android.content.Context
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import androidx.media3.common.C
import com.andres.musicplayer.data.prefs.AudioEffectsPreferences

/**
 * Process-wide bridge between the UI and the system audio effects applied to the
 * ExoPlayer's audio session in [PlaybackService]: equalizer, bass boost, and
 * virtualizer.
 *
 * All changes go through the setters here, which apply the change to the live effect
 * AND persist it via [AudioEffectsPreferences] in one step — so "applied now" and
 * "saved for next time" can never drift apart. When effects are (re)created on a fresh
 * audio session, the saved values are read back and re-applied immediately.
 */
object AudioEffects {

    private var prefs: AudioEffectsPreferences? = null

    var equalizer: Equalizer? = null
        private set
    var bassBoost: BassBoost? = null
        private set
    var virtualizer: Virtualizer? = null
        private set

    /** Call once at startup (e.g. App.onCreate) so saved settings are available. */
    fun init(context: Context) {
        if (prefs == null) prefs = AudioEffectsPreferences(context.applicationContext)
    }

    /** Attaches the system effects to the player's audio session and restores saved state. */
    fun ensureEffects(audioSessionId: Int) {
        if (audioSessionId == C.AUDIO_SESSION_ID_UNSET || audioSessionId == 0) return
        if (equalizer == null) {
            equalizer = runCatching { Equalizer(0, audioSessionId) }.getOrNull()
                ?.also { applyEqFromPrefs(it) }
        }
        if (bassBoost == null) {
            bassBoost = runCatching { BassBoost(0, audioSessionId) }.getOrNull()
                ?.also { applyBassFromPrefs(it) }
        }
        if (virtualizer == null) {
            virtualizer = runCatching { Virtualizer(0, audioSessionId) }.getOrNull()
                ?.also { applyVirtualizerFromPrefs(it) }
        }
    }

    // --- Equalizer ---

    fun setEqEnabled(value: Boolean) {
        equalizer?.let { runCatching { it.enabled = value } }
        prefs?.eqEnabled = value
    }

    fun setEqBandLevel(band: Short, level: Short) {
        val eq = equalizer ?: return
        runCatching { eq.setBandLevel(band, level) }
        prefs?.eqBands = readBands(eq)
    }

    fun applyEqPreset(preset: Short) {
        val eq = equalizer ?: return
        runCatching { eq.usePreset(preset) }
        prefs?.eqBands = readBands(eq)
    }

    private fun readBands(eq: Equalizer): List<Short> =
        (0 until eq.numberOfBands.toInt()).map {
            runCatching { eq.getBandLevel(it.toShort()) }.getOrDefault(0)
        }

    private fun applyEqFromPrefs(eq: Equalizer) {
        val p = prefs ?: return
        val bands = p.eqBands
        if (bands.isNotEmpty() && bands.size == eq.numberOfBands.toInt()) {
            bands.forEachIndexed { i, level ->
                runCatching { eq.setBandLevel(i.toShort(), level) }
            }
        }
        runCatching { eq.enabled = p.eqEnabled }
    }

    // --- Bass boost ---

    fun setBassEnabled(value: Boolean) {
        bassBoost?.let { runCatching { it.enabled = value } }
        prefs?.bassEnabled = value
    }

    fun setBassStrength(value: Short) {
        bassBoost?.let { runCatching { it.setStrength(value) } }
        prefs?.bassStrength = value
    }

    private fun applyBassFromPrefs(bass: BassBoost) {
        val p = prefs ?: return
        runCatching { if (bass.strengthSupported) bass.setStrength(p.bassStrength) }
        runCatching { bass.enabled = p.bassEnabled }
    }

    // --- Virtualizer ---

    fun setVirtualizerEnabled(value: Boolean) {
        virtualizer?.let { runCatching { it.enabled = value } }
        prefs?.virtualizerEnabled = value
    }

    fun setVirtualizerStrength(value: Short) {
        virtualizer?.let { runCatching { it.setStrength(value) } }
        prefs?.virtualizerStrength = value
    }

    private fun applyVirtualizerFromPrefs(virt: Virtualizer) {
        val p = prefs ?: return
        runCatching { if (virt.strengthSupported) virt.setStrength(p.virtualizerStrength) }
        runCatching { virt.enabled = p.virtualizerEnabled }
    }

    fun release() {
        equalizer?.release()
        bassBoost?.release()
        virtualizer?.release()
        equalizer = null
        bassBoost = null
        virtualizer = null
    }
}
