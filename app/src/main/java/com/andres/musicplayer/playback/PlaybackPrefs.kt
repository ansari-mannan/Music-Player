package com.andres.musicplayer.playback

import android.content.Context
import android.content.SharedPreferences
import androidx.media3.common.Player
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Player settings that should outlive the process: mono, repeat mode and speed. Shuffle
 * is persisted by PlayerState, which owns the shuffled order.
 */
object PlaybackPrefs {
    private const val PREFS = "playback_prefs"
    private const val KEY_MONO = "mono"
    private const val KEY_REPEAT = "repeat_mode"
    private const val KEY_SPEED = "speed"

    private var prefs: SharedPreferences? = null
    private val _mono = MutableStateFlow(false)
    val mono: StateFlow<Boolean> = _mono.asStateFlow()

    /** Read on the audio thread for every buffer, hence the plain volatile field. */
    @Volatile
    var monoEnabled: Boolean = false
        private set

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).also {
            monoEnabled = it.getBoolean(KEY_MONO, false)
            _mono.value = monoEnabled
        }
    }

    fun setMono(on: Boolean) {
        monoEnabled = on
        _mono.value = on
        prefs?.edit()?.putBoolean(KEY_MONO, on)?.apply()
    }

    var repeatMode: Int
        get() = prefs?.getInt(KEY_REPEAT, Player.REPEAT_MODE_OFF) ?: Player.REPEAT_MODE_OFF
        set(value) { prefs?.edit()?.putInt(KEY_REPEAT, value)?.apply() }

    var speed: Float
        get() = prefs?.getFloat(KEY_SPEED, 1f) ?: 1f
        set(value) { prefs?.edit()?.putFloat(KEY_SPEED, value)?.apply() }
}
