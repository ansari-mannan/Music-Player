package com.andres.musicplayer.playback

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The sleep timer's deadline, shared between the UI (which sets and shows it) and
 * [PlaybackService] (which enforces it). It used to live in the UI's player state, so
 * swiping the app away while music played meant the timer silently never fired.
 *
 * Stored as an `elapsedRealtime` deadline, which keeps counting through deep sleep.
 */
object SleepTimer {
    private const val PREFS = "player_state"
    private const val KEY_END = "sleep_end_ms"

    private var prefs: SharedPreferences? = null
    private val _endElapsedMs = MutableStateFlow<Long?>(null)

    /** Deadline in [SystemClock.elapsedRealtime] terms, or null when no timer is set. */
    val endElapsedMs: StateFlow<Long?> = _endElapsedMs.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        val saved = p.getLong(KEY_END, -1L)
        if (saved > SystemClock.elapsedRealtime()) {
            _endElapsedMs.value = saved
        } else {
            p.edit().remove(KEY_END).apply()
        }
    }

    fun set(minutes: Int?) {
        if (minutes == null) {
            cancel()
            return
        }
        val end = SystemClock.elapsedRealtime() + minutes * 60_000L
        _endElapsedMs.value = end
        prefs?.edit()?.putLong(KEY_END, end)?.apply()
    }

    fun cancel() {
        _endElapsedMs.value = null
        prefs?.edit()?.remove(KEY_END)?.apply()
    }

    fun remainingMs(): Long = _endElapsedMs.value?.let { (it - SystemClock.elapsedRealtime()).coerceAtLeast(0L) } ?: 0L
}
