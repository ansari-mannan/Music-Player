package com.andres.musicplayer.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.andres.musicplayer.App
import com.andres.musicplayer.R
import com.andres.musicplayer.data.prefs.SyncInterval
import com.andres.musicplayer.data.prefs.SyncPreferences
import java.util.concurrent.TimeUnit

/**
 * The periodic half of the sync trigger. Checks watched playlists for new videos and
 * hands anything new to [DownloadEngine]; it never downloads anything itself.
 *
 * **Best-effort by design.** Android decides when periodic work actually runs — Doze,
 * battery optimisation and app standby buckets can all delay or skip a period. The
 * Settings copy says so; nothing here should imply a guarantee. The app-open trigger is
 * the reliable one.
 *
 * The worker promotes itself to foreground work before syncing. That is not for the
 * notification's sake: from Android 12 an app in the background may not start a
 * foreground service, so without this the sync would queue downloads that then sit
 * untouched until the app was next opened.
 */
class PlaylistSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as App
        return try {
            runCatching { setForeground(getForegroundInfo()) }
                .onFailure { Log.w(TAG, "Couldn't run sync in the foreground", it) }

            val result = app.playlistSync.sync()
            Log.i(TAG, "Periodic sync: ${result?.describe() ?: "skipped, already running"}")

            // Downloads only actually start while the app counts as foreground, which is
            // what setForeground above buys. If that failed, the rows stay QUEUED and
            // start when the app is next opened.
            if ((result?.queued ?: 0) > 0) DownloadService.ensureRunning(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Periodic sync failed", e)
            // Retry once via WorkManager's backoff; a flat failure would wait a whole period.
            if (runAttemptCount < 1) Result.retry() else Result.success()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        createChannel()
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.logo_wave)
            .setContentTitle("Checking playlists for new songs")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Playlist sync",
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = "Shown briefly while checking YouTube playlists for new songs."
            setShowBadge(false)
        }
        applicationContext.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "PlaylistSyncWorker"
        private const val CHANNEL_ID = "playlist_sync"
        private const val NOTIFICATION_ID = 4202
        private const val WORK_NAME = "youtube_playlist_sync"

        /**
         * Applies the user's chosen interval, replacing any existing schedule. Call on
         * startup and whenever the setting changes.
         */
        fun schedule(context: Context, interval: SyncInterval) {
            val manager = WorkManager.getInstance(context.applicationContext)
            if (interval == SyncInterval.OFF) {
                manager.cancelUniqueWork(WORK_NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<PlaylistSyncWorker>(
                interval.minutes,
                TimeUnit.MINUTES,
            ).setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            ).build()

            // UPDATE keeps the existing schedule's place in the queue when the interval
            // is unchanged, instead of resetting the timer on every app launch.
            manager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        /** Re-applies whatever is currently stored. */
        fun schedule(context: Context) =
            schedule(context, SyncPreferences(context).interval)
    }
}
