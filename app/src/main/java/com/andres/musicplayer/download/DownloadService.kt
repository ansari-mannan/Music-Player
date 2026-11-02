package com.andres.musicplayer.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.andres.musicplayer.App
import com.andres.musicplayer.MainActivity
import com.andres.musicplayer.R
import com.andres.musicplayer.data.local.entity.DownloadEntryWithSource
import com.andres.musicplayer.data.local.entity.DownloadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the process alive and shows the ongoing notification while [DownloadEngine] has
 * work. It owns no download logic itself — the engine is an application-scoped object,
 * so downloads keep running across a configuration change and the UI can observe them
 * without binding to anything.
 *
 * The service stops itself as soon as nothing is queued, running or paused, so it never
 * lingers as a foreground service with nothing to do.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private var startedForeground = false

    private val engine by lazy { (application as App).downloadEngine }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE_ALL -> engine.pauseAll()
            ACTION_RESUME_ALL -> engine.resumeAll()
        }

        // Post something immediately: Android requires startForeground within seconds of
        // the service starting, well before the first database read could come back.
        if (!startedForeground) {
            startForegroundCompat(buildNotification(emptyList()))
            startedForeground = true
        }

        if (observer == null) {
            scope.launch { engine.recoverInterrupted() }
            observer = scope.launch {
                engine.activeEntries.collectLatest { entries ->
                    if (entries.isEmpty()) {
                        stopSelf()
                    } else {
                        notificationManager()?.notify(NOTIFICATION_ID, buildNotification(entries))
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        observer = null
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(rows: List<DownloadEntryWithSource>): Notification {
        val entries = rows.map { it.entry }
        val running = entries.filter { it.state == DownloadState.DOWNLOADING }
        val done = entries.count { it.state == DownloadState.COMPLETED }
        val allPaused = entries.isNotEmpty() && entries.all { it.state == DownloadState.PAUSED }

        val title = when {
            entries.isEmpty() -> "Preparing downloads…"
            allPaused -> "Downloads paused"
            else -> "Downloading ${done + running.size.coerceAtLeast(1)} of ${entries.size}"
        }
        val text = running.joinToString(", ") { it.title }
            .ifBlank { entries.firstOrNull()?.title.orEmpty() }

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.logo_wave)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(!allPaused)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        // Indeterminate rather than a byte total: the summed progress of several streams
        // whose sizes may not be known yet would jump around misleadingly.
        if (!allPaused && running.isNotEmpty()) {
            val known = running.mapNotNull { entry ->
                entry.totalBytes?.takeIf { it > 0 }?.let { entry.bytesDownloaded * 100 / it }
            }
            if (known.size == running.size && known.isNotEmpty()) {
                builder.setProgress(100, (known.sum() / known.size).toInt(), false)
            } else {
                builder.setProgress(0, 0, true)
            }
        }

        if (allPaused) {
            builder.addAction(0, "Resume all", action(ACTION_RESUME_ALL))
        } else {
            builder.addAction(0, "Pause all", action(ACTION_PAUSE_ALL))
        }
        return builder.build()
    }

    private fun action(name: String): PendingIntent = PendingIntent.getService(
        this,
        name.hashCode(),
        Intent(this, DownloadService::class.java).setAction(name),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notificationManager(): NotificationManager? =
        getSystemService(NotificationManager::class.java)

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Downloads",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Progress for songs being downloaded from YouTube."
            setShowBadge(false)
        }
        notificationManager()?.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "DownloadService"
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 4201

        const val ACTION_PAUSE_ALL = "com.andres.musicplayer.PAUSE_ALL_DOWNLOADS"
        const val ACTION_RESUME_ALL = "com.andres.musicplayer.RESUME_ALL_DOWNLOADS"

        /**
         * Starts the service if it isn't already up.
         *
         * Android 12+ forbids starting a foreground service from the background, so this
         * is safe from the UI but will throw once the background sync trigger calls it —
         * that path needs an expedited WorkManager job instead, and is handled when the
         * sync trigger is built.
         */
        fun ensureRunning(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't start the download service from the background", e)
            }
        }
    }
}
