package com.andres.musicplayer.ui.player

import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.andres.musicplayer.data.local.entity.Song
import com.andres.musicplayer.data.local.entity.SongWithTags
import com.andres.musicplayer.data.prefs.PlaybackStatePreferences
import com.andres.musicplayer.playback.PlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Drives playback from the UI by connecting a [MediaController] to
 * [PlaybackService]. Exposes Compose state (current track, play state, position,
 * shuffle/repeat/speed, sleep timer, and the play queue) and the play/pause/skip
 * API the screens use.
 */
@Stable
class PlayerState(context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val playbackPrefs = PlaybackStatePreferences(context)
    private var controller: MediaController? = null

    // Actions requested before the controller connects, replayed in order once it does.
    private val pendingActions = ArrayDeque<MediaController.() -> Unit>()
    private var sleepEndMs: Long? = null

    var current by mutableStateOf<SongWithTags?>(null)
        private set

    /** The full play queue, observable so the "Up next" sheet can show it. */
    var queue by mutableStateOf<List<SongWithTags>>(emptyList())
        private set

    /** Index of the currently playing item within [queue]. */
    var currentIndex by mutableStateOf(0)
        private set

    var isPlaying by mutableStateOf(false)
        private set
    var positionMs by mutableStateOf(0L)
        private set
    var durationMs by mutableStateOf(0L)
        private set
    var shuffle by mutableStateOf(false)
        private set
    var repeatMode by mutableStateOf(Player.REPEAT_MODE_OFF)
        private set
    var speed by mutableStateOf(1f)
        private set
    var sleepTimerMinutes by mutableStateOf<Int?>(null)
        private set
    var sleepRemainingMs by mutableStateOf(0L)
        private set
    var playbackError by mutableStateOf<String?>(null)
        private set

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            isPlaying = player.isPlaying
            durationMs = player.duration.coerceAtLeast(0L)
            shuffle = player.shuffleModeEnabled
            repeatMode = player.repeatMode
            speed = player.playbackParameters.speed
            currentIndex = player.currentMediaItemIndex.coerceAtLeast(0)
            val id = player.currentMediaItem?.mediaId?.toLongOrNull()
            if (id != null) {
                queue.firstOrNull { it.song.id == id }?.let { current = it }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            playbackError = when (error.errorCode) {
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
                    "File not found — it may have been moved or deleted."
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
                    "Network error during playback."
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ->
                    "Could not decode this file."
                else -> "Playback error: ${error.message}"
            }
        }
    }

    init {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            val c = future.get()
            c.addListener(listener)
            controller = c
            isPlaying = c.isPlaying
            shuffle = c.shuffleModeEnabled
            repeatMode = c.repeatMode
            speed = c.playbackParameters.speed
            currentIndex = c.currentMediaItemIndex.coerceAtLeast(0)

            // If the service restored a queue after process death, mirror it into the
            // UI state so the mini-player / Now Playing reflect it (the controller plays
            // it already; this just rebuilds the SongWithTags list we display from).
            if (queue.isEmpty() && c.mediaItemCount > 0) {
                playbackPrefs.load()?.let { saved ->
                    queue = saved.items.map { it.toSongWithTags() }
                    currentIndex = saved.index.coerceIn(0, queue.lastIndex)
                    current = queue.getOrNull(currentIndex)
                    positionMs = saved.positionMs
                }
            }

            while (pendingActions.isNotEmpty()) {
                pendingActions.removeFirst().invoke(c)
            }
        }, ContextCompat.getMainExecutor(context))

        // Restore a sleep timer that was running before the process was killed.
        val saved = prefs.getLong(KEY_SLEEP_END, -1L)
        if (saved > SystemClock.elapsedRealtime()) {
            sleepEndMs = saved
            sleepTimerMinutes = -1 // sentinel: timer active, original minutes unknown
            sleepRemainingMs = saved - SystemClock.elapsedRealtime()
        } else {
            prefs.edit().remove(KEY_SLEEP_END).apply()
        }

        // Poll position (seek bar) and the sleep-timer countdown.
        scope.launch {
            while (isActive) {
                controller?.let { if (it.isPlaying) positionMs = it.currentPosition }
                val end = sleepEndMs
                if (end != null) {
                    val remaining = end - SystemClock.elapsedRealtime()
                    if (remaining <= 0L) {
                        controller?.pause()
                        sleepEndMs = null
                        sleepTimerMinutes = null
                        sleepRemainingMs = 0L
                        prefs.edit().remove(KEY_SLEEP_END).apply()
                    } else {
                        sleepRemainingMs = remaining
                    }
                }
                delay(500)
            }
        }
    }

    private fun withController(block: MediaController.() -> Unit) {
        val c = controller
        if (c != null) c.block() else pendingActions.addLast(block)
    }

    fun play(song: SongWithTags, newQueue: List<SongWithTags>) {
        queue = newQueue
        current = song
        val items = newQueue.map { it.toMediaItem() }
        val startIndex = newQueue.indexOfFirst { it.song.id == song.song.id }.coerceAtLeast(0)
        currentIndex = startIndex
        withController {
            setMediaItems(items, startIndex, 0L)
            prepare()
            play()
        }
    }

    /** Jumps to the queue item at [index] and plays it. */
    fun playQueueItem(index: Int) {
        if (index !in queue.indices) return
        current = queue[index]
        currentIndex = index
        withController {
            seekTo(index, 0L)
            play()
        }
    }

    /** Adds [song] to the end of the current queue without interrupting playback. */
    fun addToQueue(song: SongWithTags) {
        queue = queue + song
        withController { addMediaItem(song.toMediaItem()) }
    }

    /** Inserts [song] immediately after the currently playing track. */
    fun playNext(song: SongWithTags) {
        val insertAt = (currentIndex + 1).coerceAtMost(queue.size)
        queue = queue.toMutableList().also { it.add(insertAt, song) }
        withController { addMediaItem(insertAt, song.toMediaItem()) }
    }

    /** Moves a queue item from [from] to [to]. */
    fun moveQueueItem(from: Int, to: Int) {
        if (from == to) return
        if (from !in queue.indices || to !in queue.indices) return
        val mutable = queue.toMutableList()
        val item = mutable.removeAt(from)
        mutable.add(to, item)
        queue = mutable
        currentIndex = controller?.currentMediaItemIndex?.coerceAtLeast(0) ?: currentIndex
        withController { moveMediaItem(from, to) }
    }

    /** Removes the item at [index] from the queue (cannot remove the current track). */
    fun removeFromQueue(index: Int) {
        if (index == currentIndex || index !in queue.indices) return
        queue = queue.toMutableList().also { it.removeAt(index) }
        withController { removeMediaItem(index) }
    }

    fun togglePlayPause() = withController {
        if (isPlaying) pause() else play()
    }

    fun next() = withController { seekToNextMediaItem() }

    fun previous() = withController { seekToPreviousMediaItem() }

    fun seekTo(ms: Long) = withController { seekTo(ms) }

    fun toggleShuffle() = withController { shuffleModeEnabled = !shuffleModeEnabled }

    /** Cycles repeat: off → all (loop playlist) → one (loop track) → off. */
    fun cycleRepeat() = withController {
        repeatMode = when (repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun changeSpeed(value: Float) = withController { setPlaybackSpeed(value) }

    /** Schedules a pause after [minutes], or cancels the timer when null. */
    fun setSleepTimer(minutes: Int?) {
        sleepTimerMinutes = minutes
        if (minutes != null) {
            val end = SystemClock.elapsedRealtime() + minutes * 60_000L
            sleepEndMs = end
            sleepRemainingMs = minutes * 60_000L
            prefs.edit().putLong(KEY_SLEEP_END, end).apply()
        } else {
            sleepEndMs = null
            sleepRemainingMs = 0L
            prefs.edit().remove(KEY_SLEEP_END).apply()
        }
    }

    fun clearPlaybackError() { playbackError = null }

    fun release() {
        scope.cancel()
        controller?.removeListener(listener)
        controller?.release()
        controller = null
    }

    private companion object {
        const val PREFS_NAME = "player_state"
        const val KEY_SLEEP_END = "sleep_end_ms"
    }
}

private fun SongWithTags.toMediaItem(): MediaItem =
    MediaItem.Builder()
        .setMediaId(song.id.toString())
        .setUri(song.uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(song.title)
                .setArtist(song.artist)
                .build(),
        )
        .build()

/** Rebuilds a display-only [SongWithTags] from a saved queue item (no tags/duration). */
private fun PlaybackStatePreferences.Item.toSongWithTags(): SongWithTags =
    SongWithTags(
        song = Song(id = id, uri = uri, title = title, artist = artist, durationMs = 0L),
        tags = emptyList(),
    )

@Composable
fun rememberPlayerState(): PlayerState {
    val context = LocalContext.current
    val state = remember { PlayerState(context.applicationContext) }
    DisposableEffect(Unit) {
        onDispose { state.release() }
    }
    return state
}
