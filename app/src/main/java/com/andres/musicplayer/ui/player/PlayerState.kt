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
 *
 * Queue model (VLC's `PlaylistManager`): the queue is the single source of truth for
 * what plays next. Playlists / albums / folders only ever *load* the queue, and always
 * replace it wholesale. Shuffle is implemented by physically reordering that queue —
 * ExoPlayer's own shuffle mode is never used, because it keeps a hidden shuffle order
 * over the unmodified list that the visible queue can't reflect (and which ends
 * playback early once its internal index runs out).
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

    /**
     * The queue order from before shuffle was turned on, restored when it is turned off.
     * Empty whenever shuffle is off. Kept in sync as items are added to / removed from
     * the live queue while shuffle is active.
     */
    private var originalQueue: List<Song> = emptyList()

    /**
     * Media ids of tracks that genuinely played, most recent last, so Previous walks back
     * through actual listening history rather than blindly stepping to `index - 1`.
     */
    private val playedHistory = ArrayDeque<String>()
    private var lastMediaId: String? = null

    /** Set while stepping backwards so the resulting transition isn't pushed onto the history. */
    private var navigatingBack = false

    var current by mutableStateOf<Song?>(null)
        private set

    /** The full play queue, observable so the "Up next" sheet can show it. */
    var queue by mutableStateOf<List<Song>>(emptyList())
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
            // The ticker below only runs while playing; a seek or track change while
            // paused would otherwise leave the bar showing the old position.
            if (events.containsAny(
                    Player.EVENT_POSITION_DISCONTINUITY,
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_PLAYBACK_STATE_CHANGED,
                )
            ) {
                positionMs = player.currentPosition.coerceAtLeast(0L)
            }
            repeatMode = player.repeatMode
            speed = player.playbackParameters.speed
            val index = player.currentMediaItemIndex.coerceAtLeast(0)
            currentIndex = index
            // Resolve by position first — the queue can legitimately hold the same song
            // twice (via "Add to queue"), so an id-only lookup would pick the wrong row.
            val byIndex = queue.getOrNull(index)
            val id = player.currentMediaItem?.mediaId?.toLongOrNull()
            current = when {
                byIndex != null && (id == null || byIndex.id == id) -> byIndex
                id != null -> queue.firstOrNull { it.id == id } ?: current
                else -> current
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val newId = mediaItem?.mediaId
            val previousId = lastMediaId
            if (previousId != null && previousId != newId) {
                if (navigatingBack) {
                    navigatingBack = false
                } else {
                    playedHistory.addLast(previousId)
                    while (playedHistory.size > MAX_HISTORY) playedHistory.removeFirst()
                }
            }
            lastMediaId = newId
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
            // Shuffle is ours to manage: the player's own shuffle order stays off forever.
            c.shuffleModeEnabled = false
            isPlaying = c.isPlaying
            repeatMode = c.repeatMode
            speed = c.playbackParameters.speed
            currentIndex = c.currentMediaItemIndex.coerceAtLeast(0)
            lastMediaId = c.currentMediaItem?.mediaId

            // If the service restored a queue after process death, mirror it into the
            // UI state so the mini-player / Now Playing reflect it (the controller plays
            // it already; this just rebuilds the Song list we display from).
            if (queue.isEmpty() && c.mediaItemCount > 0) {
                playbackPrefs.load()?.let { saved ->
                    queue = saved.items.map { it.toSong() }
                    currentIndex = saved.index.coerceIn(0, queue.lastIndex)
                    current = queue.getOrNull(currentIndex)
                    positionMs = saved.positionMs
                }
            }
            restoreShuffleState()

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

    // ----------------------------------------------------------- loading

    /**
     * Replaces the whole queue with [newQueue] and starts playing [song]. Every "play this
     * collection" entry point funnels through here — the queue is never appended to except
     * via [playNext] / [addToQueue]. If shuffle is on, the new queue is loaded shuffled.
     */
    fun play(song: Song, newQueue: List<Song>) {
        if (newQueue.isEmpty()) return
        val startIndex = newQueue.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
        loadQueue(newQueue, startIndex, shuffled = shuffle)
    }

    /**
     * Turns shuffle on and plays [newQueue] in a freshly shuffled order, starting from a
     * random track rather than always the first one.
     */
    fun playShuffled(newQueue: List<Song>) {
        if (newQueue.isEmpty()) return
        shuffle = true
        loadQueue(newQueue, newQueue.indices.random(), shuffled = true)
    }

    /**
     * Full queue replacement. When [shuffled], the queue is physically reordered with the
     * starting track at index 0 and [originalQueue] keeps the source order for later restore.
     */
    private fun loadQueue(source: List<Song>, startIndex: Int, shuffled: Boolean) {
        val start = startIndex.coerceIn(0, source.lastIndex)
        val effective: List<Song>
        val effectiveIndex: Int
        if (shuffled) {
            val order = listOf(start) + (source.indices - start).shuffled()
            effective = order.map { source[it] }
            effectiveIndex = 0
            originalQueue = source
        } else {
            effective = source
            effectiveIndex = start
            originalQueue = emptyList()
        }
        queue = effective
        currentIndex = effectiveIndex
        current = effective[effectiveIndex]
        playedHistory.clear()
        navigatingBack = false
        lastMediaId = effective[effectiveIndex].id.toString()
        persistShuffleState()

        val items = effective.map { it.toMediaItem() }
        withController {
            shuffleModeEnabled = false
            clearMediaItems()
            setMediaItems(items, effectiveIndex, 0L)
            prepare()
            play()
        }
    }

    // ----------------------------------------------------------- shuffle

    /**
     * Toggles shuffle by physically reordering the queue. On: the queue is shuffled with
     * the playing track moved to index 0. Off: the pre-shuffle order is restored with the
     * playing track at its original index. Either way playback continues uninterrupted
     * from the same position in the same song.
     */
    fun toggleShuffle() {
        if (shuffle) disableShuffle() else enableShuffle()
    }

    private fun enableShuffle() {
        shuffle = true
        val c = controller
        if (c == null || queue.isEmpty()) {
            persistShuffleState()
            return
        }
        val playingIndex = c.currentMediaItemIndex.coerceIn(0, queue.lastIndex)
        val order = listOf(playingIndex) + (queue.indices - playingIndex).shuffled()
        originalQueue = queue
        val shuffled = order.map { queue[it] }
        queue = shuffled
        currentIndex = 0
        persistShuffleState()
        reseat(c, shuffled, 0)
    }

    private fun disableShuffle() {
        shuffle = false
        val c = controller
        val original = originalQueue
        originalQueue = emptyList()
        persistShuffleState()
        if (c == null || original.isEmpty()) return

        val playing = queue.getOrNull(c.currentMediaItemIndex.coerceAtLeast(0))
        val restoredIndex = if (playing == null) {
            0
        } else {
            original.indexOfFirst { it === playing }
                .takeIf { it >= 0 }
                ?: original.indexOfFirst { it.id == playing.id }.coerceAtLeast(0)
        }
        queue = original
        currentIndex = restoredIndex
        reseat(c, original, restoredIndex)
    }

    /**
     * Swaps the player's media items for [items] without interrupting what's playing:
     * keeps the current position and resumes if it was playing.
     */
    private fun reseat(c: MediaController, items: List<Song>, index: Int) {
        val resumeAt = c.currentPosition.coerceAtLeast(0L)
        val wasPlaying = c.playWhenReady
        c.shuffleModeEnabled = false
        c.setMediaItems(items.map { it.toMediaItem() }, index, resumeAt)
        c.prepare()
        if (wasPlaying) c.play()
    }

    private fun persistShuffleState() {
        prefs.edit()
            .putBoolean(KEY_SHUFFLE, shuffle)
            .putString(
                KEY_ORIGINAL_ORDER,
                if (shuffle && originalQueue.isNotEmpty()) {
                    originalQueue.joinToString(",") { it.id.toString() }
                } else {
                    null
                },
            )
            .apply()
    }

    /**
     * Rebuilds [shuffle] / [originalQueue] after a process restart, so turning shuffle off
     * still restores the order the queue had before it was shuffled.
     */
    private fun restoreShuffleState() {
        shuffle = prefs.getBoolean(KEY_SHUFFLE, false)
        if (!shuffle || queue.isEmpty()) return
        val ids = prefs.getString(KEY_ORIGINAL_ORDER, null)
            ?.split(",")
            ?.mapNotNull { it.toLongOrNull() }
            ?: return
        val pool = queue.toMutableList()
        val rebuilt = mutableListOf<Song>()
        ids.forEach { id ->
            val at = pool.indexOfFirst { it.id == id }
            if (at >= 0) rebuilt += pool.removeAt(at)
        }
        rebuilt += pool // anything the saved order didn't cover keeps its relative position
        originalQueue = rebuilt
    }

    // ----------------------------------------------------------- queue edits

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
    fun addToQueue(song: Song) {
        queue = queue + song
        if (shuffle && originalQueue.isNotEmpty()) originalQueue = originalQueue + song
        withController { addMediaItem(song.toMediaItem()) }
    }

    /** Inserts [song] immediately after the currently playing track. */
    fun playNext(song: Song) {
        val insertAt = (currentIndex + 1).coerceIn(0, queue.size)
        val playing = queue.getOrNull(currentIndex)
        queue = queue.toMutableList().also { it.add(insertAt, song) }
        if (shuffle && originalQueue.isNotEmpty()) {
            val afterPlaying = playing
                ?.let { p -> originalQueue.indexOfFirst { it === p } }
                ?.takeIf { it >= 0 }
                ?.plus(1)
                ?: originalQueue.size
            originalQueue = originalQueue.toMutableList().also { it.add(afterPlaying, song) }
        }
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
        val removed = queue[index]
        queue = queue.toMutableList().also { it.removeAt(index) }
        if (shuffle && originalQueue.isNotEmpty()) {
            val at = originalQueue.indexOfFirst { it === removed }
            if (at >= 0) originalQueue = originalQueue.toMutableList().also { it.removeAt(at) }
        }
        withController { removeMediaItem(index) }
    }

    // ----------------------------------------------------------- transport

    /**
     * Keyed on [Player.getPlayWhenReady], not `isPlaying`: while a track is buffering
     * `isPlaying` is false, so a tap meant as Pause would call play() and do nothing.
     */
    fun togglePlayPause() = withController {
        if (playWhenReady) pause() else play()
    }

    /** The queue is already in play order, so Next is a plain step forward. */
    fun next() = withController { seekToNextMediaItem() }

    /**
     * Restarts the current track if we're more than [RESTART_THRESHOLD_MS] into it
     * (avoids accidental skip-backs after a long listen), otherwise steps back to the
     * track that genuinely played before this one.
     */
    fun previous() {
        val c = controller
        if (c == null) {
            pendingActions.addLast { seekToPreviousMediaItem() }
            return
        }
        if (c.currentPosition > RESTART_THRESHOLD_MS) {
            c.seekTo(0L)
            positionMs = 0L
            return
        }
        val playingIndex = c.currentMediaItemIndex
        while (playedHistory.isNotEmpty()) {
            val id = playedHistory.removeLast()
            val index = queue.indexOfFirst { it.id.toString() == id }
            if (index >= 0 && index != playingIndex) {
                navigatingBack = true
                c.seekTo(index, 0L)
                currentIndex = index
                current = queue[index]
                positionMs = 0L
                return
            }
        }
        // Nothing genuinely played before this: fall back to the queue's own order.
        if (c.hasPreviousMediaItem()) c.seekToPreviousMediaItem() else c.seekTo(0L)
    }

    fun seekTo(ms: Long) {
        // Moves the bar now rather than snapping back until the next 500 ms tick.
        positionMs = ms.coerceAtLeast(0L)
        withController { seekTo(ms) }
    }

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

    /**
     * Fully stops playback and tears the session down: clears the queue and the saved
     * playback state, and stops the player so the media notification is removed. Used
     * when the user swipes the mini-player away to shut the app off entirely.
     */
    fun stopAndClear() {
        queue = emptyList()
        originalQueue = emptyList()
        playedHistory.clear()
        lastMediaId = null
        current = null
        currentIndex = 0
        isPlaying = false
        positionMs = 0L
        playbackPrefs.clear()
        persistShuffleState()
        withController {
            stop()
            clearMediaItems()
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
        const val KEY_SHUFFLE = "shuffle_enabled"
        const val KEY_ORIGINAL_ORDER = "shuffle_original_order"

        /** Past this far into a track, Previous restarts it instead of going back (VLC). */
        const val RESTART_THRESHOLD_MS = 5_000L
        const val MAX_HISTORY = 100
    }
}

private fun Song.toMediaItem(): MediaItem =
    MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .build(),
        )
        .build()

/** Rebuilds a display-only [Song] from a saved queue item (no duration). */
private fun PlaybackStatePreferences.Item.toSong(): Song =
    Song(id = id, uri = uri, title = title, artist = artist, durationMs = 0L)

@Composable
fun rememberPlayerState(): PlayerState {
    val context = LocalContext.current
    val state = remember { PlayerState(context.applicationContext) }
    DisposableEffect(Unit) {
        onDispose { state.release() }
    }
    return state
}