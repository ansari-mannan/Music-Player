package com.andres.musicplayer.ui.downloads

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.andres.musicplayer.App
import com.andres.musicplayer.data.local.entity.DownloadEntryWithSource
import com.andres.musicplayer.data.prefs.DownloadPreferences
import com.andres.musicplayer.download.DownloadProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Compose-facing view of the download queue. */
@Stable
class DownloadsState(context: Context) {

    private val engine = (context.applicationContext as App).downloadEngine
    private val prefs = DownloadPreferences(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    var active by mutableStateOf<List<DownloadEntryWithSource>>(emptyList())
        private set
    var failed by mutableStateOf<List<DownloadEntryWithSource>>(emptyList())
        private set
    var completed by mutableStateOf<List<DownloadEntryWithSource>>(emptyList())
        private set

    /** Live byte counts, fresher than the periodically-persisted values in Room. */
    var progress by mutableStateOf<Map<String, DownloadProgress>>(emptyMap())
        private set

    var concurrency by mutableStateOf(prefs.concurrency.value)
        private set

    /** Set when a pasted link couldn't be understood, or the video is already queued. */
    var message by mutableStateOf<String?>(null)
        private set

    init {
        scope.launch { engine.activeEntries.collect { active = it } }
        scope.launch { engine.failedEntries.collect { failed = it } }
        scope.launch { engine.completedEntries.collect { completed = it } }
        scope.launch { engine.progress.collect { progress = it } }
        scope.launch { prefs.concurrency.collect { concurrency = it } }
    }

    /** Accepts a full YouTube link or a bare video id. */
    fun enqueue(input: String) {
        val videoId = parseVideoId(input)
        if (videoId == null) {
            message = "That doesn't look like a YouTube link or video id."
            return
        }
        scope.launch {
            val added = engine.enqueue(
                videoId = videoId,
                title = videoId,
                thumbnailUrl = "https://i.ytimg.com/vi/$videoId/mqdefault.jpg",
            )
            message = if (added) null else "That video is already queued or downloaded."
        }
    }

    fun updateConcurrency(value: Int) = prefs.setConcurrency(value)

    fun pause(id: String) {
        engine.pause(id)
    }

    fun resume(id: String) {
        engine.resume(id)
    }

    fun cancel(id: String) {
        engine.cancel(id)
    }

    fun retry(id: String) {
        engine.retry(id)
    }

    fun retryAllFailed() {
        engine.retryAllFailed()
    }

    fun pauseAll() {
        engine.pauseAll()
    }

    fun resumeAll() {
        engine.resumeAll()
    }

    fun removeFromHistory(id: String) {
        engine.removeFromHistory(id)
    }

    fun clearMessage() {
        message = null
    }

    fun dispose() = scope.cancel()
}

/**
 * Pulls the 11-character video id out of anything YouTube hands you: a watch link, a
 * `youtu.be` short link, a Shorts or embed path, or the bare id itself.
 */
internal fun parseVideoId(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null
    if (trimmed.matches(BARE_ID)) return trimmed

    val patterns = listOf(
        Regex("""[?&]v=([A-Za-z0-9_-]{11})"""),
        Regex("""youtu\.be/([A-Za-z0-9_-]{11})"""),
        Regex("""/(?:shorts|embed|live)/([A-Za-z0-9_-]{11})"""),
    )
    return patterns.firstNotNullOfOrNull { it.find(trimmed)?.groupValues?.get(1) }
}

private val BARE_ID = Regex("""[A-Za-z0-9_-]{11}""")

@Composable
fun rememberDownloadsState(): DownloadsState {
    val context = LocalContext.current
    val state = remember { DownloadsState(context) }
    DisposableEffect(state) { onDispose { state.dispose() } }
    return state
}
