package com.andres.musicplayer.data.prefs

import android.content.Context

/** How often the background check runs. [OFF] disables it; app-open sync still happens. */
enum class SyncInterval(val minutes: Long, val label: String) {
    OFF(0, "Off"),
    EVERY_15_MIN(15, "Every 15 minutes"),
    EVERY_30_MIN(30, "Every 30 minutes"),
    HOURLY(60, "Every hour"),
    EVERY_3_HOURS(180, "Every 3 hours"),
    EVERY_6_HOURS(360, "Every 6 hours"),
    DAILY(1440, "Once a day"),
}

/**
 * Settings for playlist syncing.
 *
 * 15 minutes is the floor because that is WorkManager's own minimum period for periodic
 * work — anything shorter is silently rounded up by the framework, so offering it would
 * be a lie.
 */
class SyncPreferences(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var interval: SyncInterval
        get() = runCatching {
            SyncInterval.valueOf(prefs.getString(KEY_INTERVAL, null) ?: DEFAULT.name)
        }.getOrDefault(DEFAULT)
        set(value) = prefs.edit().putString(KEY_INTERVAL, value.name).apply()

    /** Epoch millis of the last completed sync, or 0 if one has never finished. */
    var lastSyncAt: Long
        get() = prefs.getLong(KEY_LAST_SYNC, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_SYNC, value).apply()

    /** Human-readable outcome of the last sync, shown in Settings. */
    var lastSyncSummary: String?
        get() = prefs.getString(KEY_LAST_SUMMARY, null)
        set(value) = prefs.edit().putString(KEY_LAST_SUMMARY, value).apply()

    companion object {
        private const val PREFS_NAME = "sync_prefs"
        private const val KEY_INTERVAL = "interval"
        private const val KEY_LAST_SYNC = "last_sync_at"
        private const val KEY_LAST_SUMMARY = "last_sync_summary"

        /**
         * Six hours by default: frequent enough that a new song shows up the same day
         * without the app waking the radio every quarter of an hour.
         */
        val DEFAULT = SyncInterval.EVERY_6_HOURS
    }
}
