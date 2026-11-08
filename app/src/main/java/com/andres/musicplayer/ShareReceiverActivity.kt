package com.andres.musicplayer

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.andres.musicplayer.data.prefs.FolderPreferences
import com.andres.musicplayer.download.DownloadService
import com.andres.musicplayer.ui.downloads.parseVideoId
import kotlinx.coroutines.launch

/**
 * Target for "Share → Andres' Music Player" from the YouTube app (or anywhere a link can be
 * shared). Queues the download and closes again without opening the full app, so grabbing
 * one song is a single tap from wherever you heard it.
 */
class ShareReceiverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent?.takeIf { it.action == Intent.ACTION_SEND }
            ?.getStringExtra(Intent.EXTRA_TEXT)
            .orEmpty()
        val videoId = Regex("""\S+""").findAll(text)
            .firstNotNullOfOrNull { parseVideoId(it.value) }
            ?: parseVideoId(text)

        if (videoId == null) {
            toast("That doesn't look like a YouTube link.")
            finish()
            return
        }
        if (FolderPreferences(this).watchedFolderUri == null) {
            toast("Choose a music folder in the app first.")
            finish()
            return
        }

        val app = application as App
        lifecycleScope.launch {
            val added = app.downloadEngine.enqueue(
                videoId = videoId,
                title = videoId,
                thumbnailUrl = "https://i.ytimg.com/vi/$videoId/mqdefault.jpg",
            )
            // This activity is in the foreground, so starting the service is allowed.
            DownloadService.ensureRunning(app)
            toast(if (added) "Downloading — it'll appear in your library when done." else "Already downloaded or in the queue.")
            finish()
        }
    }

    private fun toast(message: String) =
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
}
