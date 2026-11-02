package com.andres.musicplayer.data.prefs

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Download-manager settings. Currently just how many downloads may run at once.
 *
 * ### Why the flow is process-wide and not per instance
 *
 * Three places construct one of these: the Downloads tab, the same picker in Settings,
 * and [com.andres.musicplayer.download.DownloadEngine] itself. When each instance owned
 * its own `MutableStateFlow`, seeded from disk at construction, "observable so the engine
 * can resize its pool" was true of the flow and false of the app: setting the value from
 * the UI updated *that screen's* copy, wrote the number to disk, and never reached the
 * engine's copy at all. The engine went on using whatever the value had been when it was
 * built, and the setting appeared to do nothing until the app was restarted — which is
 * exactly what it did do, because a restart is when the engine re-read the disk.
 *
 * One shared flow behind every instance is what makes the setting live. The value on disk
 * stays the source of truth across launches; the flow is the source of truth within one.
 */
class DownloadPreferences(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Observable so the engine resizes its pool without being restarted. */
    val concurrency: StateFlow<Int> get() = shared(prefs).asStateFlow()

    fun setConcurrency(value: Int) {
        val clamped = value.coerceIn(MIN, MAX)
        prefs.edit().putInt(KEY_CONCURRENCY, clamped).apply()
        shared(prefs).value = clamped
    }

    companion object {
        private const val PREFS_NAME = "download_prefs"
        private const val KEY_CONCURRENCY = "concurrency"

        const val MIN = 1
        const val MAX = 3
        const val DEFAULT_CONCURRENCY = 2

        /**
         * Seeded from disk by whichever instance is built first, then shared. Held for
         * the life of the process on purpose — every reader wants the same number.
         */
        @Volatile
        private var state: MutableStateFlow<Int>? = null

        private fun shared(prefs: SharedPreferences): MutableStateFlow<Int> =
            state ?: synchronized(this) {
                state ?: MutableStateFlow(
                    prefs.getInt(KEY_CONCURRENCY, DEFAULT_CONCURRENCY).coerceIn(MIN, MAX),
                ).also { state = it }
            }
    }
}
