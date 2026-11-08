package com.andres.musicplayer.ui.player

import android.content.ComponentName
import android.content.Context
import androidx.annotation.OptIn
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
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.andres.musicplayer.App
import com.andres.musicplayer.data.local.entity.Song
import com.andres.musicplayer.data.prefs.PlaybackStatePreferences
import com.andres.musicplayer.playback.PlaybackService
import com.andres.musicplayer.playback.SleepTimer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
 *
 * The queue's songs are kept in step with the library: an edit (title, album, a trimmed
 * duration) shows up on the playing track at once, and a queue restored after a restart
 * gets its full details back rather than the bare title/artist the service saved.
 */
@Stable
@OptIn(UnstableApi::class)
class PlayerState(context: Context) {

    private val repo = (context.applicationContext as App).repository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val savedQueue = PlaybackStatePreferences(context)
    private var controller: MediaController? = null

    // Actions requested before the controller connects, replayed in order once it does.
    private val pendingActions = ArrayDeque<MediaController.() -> Unit>()

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

    /** Latest library snapshot by id, for filling in restored / edited queue items. */
    private var library: Map<Long, Song> = emptyMap()

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

    /** What the play/pause button should show — Play while ended or idle, not just paused. */
    var showPlayButton by mutableStateOf(true)
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

    /** Minutes chosen for the running sleep timer; -1 when running but chosen in another session. */
    var sleepTimerMinutes by mutableStateOf<Int?>(null)
        private set
    var sleepRemainingMs by mutableStateOf(0L)
        private set
    var playbackError by mutableStateOf<String?>(null)
        private set

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            readPlayer(player)
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
            val what = when (error.errorCode) {
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
                    "File not found — it may have been moved or deleted."
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
                    "Network error during playback."
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ->
                    "Could not decode this file."
                else -> "Playback error: ${error.message}"
            }
            // The service skips ahead to the next track on its own.
            playbackError = "$what Skipping it."
        }
    }

    /** Copies the controller's own state (the parts that don't depend on the queue). */
    private fun readPlayer(player: Player) {
        isPlaying = player.isPlaying
        showPlayButton = Util.shouldShowPlayButton(player)
        if (isPlaying) ensureTicker()
        durationMs = player.duration.coerceAtLeast(0L)
        repeatMode = player.repeatMode
        speed = player.playbackParameters.speed
    }

    init {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            c.addListener(listener)
            controller = c
            // Shuffle is ours to manage: the player's own shuffle order stays off forever.
            c.shuffleModeEnabled = false
            // Fill everything in now: no event fires until something changes, so a screen
            // rebuilt mid-song would otherwise show a 0:00 duration until the next track.
            readPlayer(c)
            positionMs = c.currentPosition.coerceAtLeast(0L)
            currentIndex = c.currentMediaItemIndex.coerceAtLeast(0)
            lastMediaId = c.currentMediaItem?.mediaId

            // The service may already hold a queue (still playing, or restored after a
            // restart). Mirror it so the mini-player / Now Playing show it, with details
            // from the library where the song is in it.
            if (queue.isEmpty() && c.mediaItemCount > 0) {
                val saved = savedQueue.load()?.items.orEmpty().associateBy { it.id }
                queue = (0 until c.mediaItemCount).map { i -> c.getMediaItemAt(i).toSong(library, saved) }
                currentIndex = currentIndex.coerceIn(0, queue.lastIndex)
                current = queue.getOrNull(currentIndex)
            }
            restoreShuffleState()

            while (pendingActions.isNotEmpty()) {
                pendingActions.removeFirst().invoke(c)
            }
        }, ContextCompat.getMainExecutor(context))

        scope.launch { repo.allSongs.collect { syncWithLibrary(it) } }
        scope.launch { repo.rewrittenUris.collect { reloadUri(it) } }
        scope.launch {
            SleepTimer.endElapsedMs.collect { end ->
                if (end == null) {
                    sleepTimerMinutes = null
                    sleepRemainingMs = 0L
                } else {
                    if (sleepTimerMinutes == null) sleepTimerMinutes = -1
                    sleepRemainingMs = SleepTimer.remainingMs()
                    ensureTicker()
                }
            }
        }
    }

    private var ticker: Job? = null

    /**
     * Polls the position (seek bar) and the sleep-timer countdown — but only while one of
     * them is actually moving. It stops itself once playback is paused and no timer is
     * set, rather than waking the CPU twice a second for the life of the app. The timer
     * itself is enforced by the playback service; this only displays it.
     */
    private fun ensureTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                val playing = controller?.isPlaying == true
                if (playing) controller?.let { positionMs = it.currentPosition }
                val timerOn = SleepTimer.endElapsedMs.value != null
                if (timerOn) sleepRemainingMs = SleepTimer.remainingMs()
                if (!playing && !timerOn) break
                delay(500)
            }
        }
    }

    private fun withController(block: MediaController.() -> Unit) {
        val c = controller
        if (c != null) c.block() else pendingActions.addLast(block)
    }

    // ----------------------------------------------------------- library sync

    /**
     * Swaps in the library's current version of every queued song: edited titles, albums,
     * durations, and files that moved. The notification is updated for the ones whose
     * displayed text changed.
     */
    private fun syncWithLibrary(all: List<Song>) {
        library = all.associateBy { it.id }
        if (queue.isEmpty()) return
        val old = queue
        var changed = false
        val updated = old.map { song ->
            val fresh = library[song.id]
            if (fresh != null && fresh != song) {
                changed = true
                fresh
            } else {
                song
            }
        }
        if (!changed) return
        queue = updated
        originalQueue = originalQueue.map { library[it.id] ?: it }
        current = current?.let { cur -> updated.getOrNull(currentIndex)?.takeIf { it.id == cur.id } ?: library[cur.id] ?: cur }

        val c = controller ?: return
        updated.forEachIndexed { i, song ->
            val before = old[i]
            if (i >= c.mediaItemCount) return@forEachIndexed
            when {
                // A blank uri is a stand-in that never reached the player; nothing to reload.
                song.uri != before.uri && before.uri.isNotBlank() ->
                    replaceKeepingPosition(c, i, song.toMediaItem())
                song.title != before.title || song.artist != before.artist ->
                    c.replaceMediaItem(i, song.toMediaItem())
            }
        }
    }

    /**
     * A file in the queue was just rewritten in place (tags, artwork, a trim). The player
     * may have parts of the old bytes buffered, so it's reloaded from where it was.
     */
    private fun reloadUri(uri: String) {
        val c = controller ?: return
        queue.forEachIndexed { i, song ->
            if (song.uri == uri && i < c.mediaItemCount) {
                replaceKeepingPosition(c, i, song.toMediaItem(forceReload = true))
            }
        }
    }

    private fun replaceKeepingPosition(c: MediaController, index: Int, item: MediaItem) {
        if (index != c.currentMediaItemIndex) {
            c.replaceMediaItem(index, item)
            return
        }
        val position = c.currentPosition
        val playWhenReady = c.playWhenReady
        c.replaceMediaItem(index, item)
        c.seekTo(index, position)
        c.prepare()
        c.playWhenReady = playWhenReady
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
            setMediaItems(items, effectiveIndex, 0L)
            prepare()
            play()
        }
    }

    // ----------------------------------------------------------- shuffle

    /**
     * Toggles shuffle by physically reordering the queue. On: the queue is shuffled with
     * the playing track moved to index 0. Off: the pre-shuffle order is restored with the
     * playing track at its original index. Either way the playing track is never touched,
     * so there's no gap or restart.
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
        reorderAroundCurrent(c, shuffled, 0)
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
        reorderAroundCurrent(c, original, restoredIndex)
    }

    /**
     * Rebuilds the player's list as [items] (with the playing track landing at [newIndex])
     * by removing and re-adding everything *around* the playing item. Replacing the whole
     * list instead made ExoPlayer re-open the current file, which was an audible gap.
     */
    private fun reorderAroundCurrent(c: MediaController, items: List<Song>, newIndex: Int) {
        val cur = c.currentMediaItemIndex
        val count = c.mediaItemCount
        if (cur !in 0 until count || newIndex !in items.indices) {
            val resumeAt = c.currentPosition.coerceAtLeast(0L)
            c.setMediaItems(items.map { it.toMediaItem() }, newIndex.coerceIn(0, items.lastIndex), resumeAt)
            c.prepare()
            return
        }
        if (cur + 1 < count) c.removeMediaItems(cur + 1, count)
        if (cur > 0) c.removeMediaItems(0, cur)
        if (newIndex > 0) c.addMediaItems(0, items.subList(0, newIndex).map { it.toMediaItem() })
        if (newIndex + 1 < items.size) {
            c.addMediaItems(newIndex + 1, items.subList(newIndex + 1, items.size).map { it.toMediaItem() })
        }
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
    fun addToQueue(song: Song) = addToQueue(listOf(song))

    fun addToQueue(songs: List<Song>) {
        if (songs.isEmpty()) return
        if (queue.isEmpty()) {
            play(songs.first(), songs)
            return
        }
        queue = queue + songs
        if (shuffle && originalQueue.isNotEmpty()) originalQueue = originalQueue + songs
        withController { addMediaItems(songs.map { it.toMediaItem() }) }
    }

    /** Inserts [song] immediately after the currently playing track. */
    fun playNext(song: Song) = playNext(listOf(song))

    /**
     * Inserts [songs] right after the playing track, in the order given — so selecting
     * A, B, C and choosing Play next plays A, then B, then C.
     */
    fun playNext(songs: List<Song>) {
        if (songs.isEmpty()) return
        if (queue.isEmpty()) {
            play(songs.first(), songs)
            return
        }
        val insertAt = (currentIndex + 1).coerceIn(0, queue.size)
        val playing = queue.getOrNull(currentIndex)
        queue = queue.toMutableList().also { it.addAll(insertAt, songs) }
        if (shuffle && originalQueue.isNotEmpty()) {
            val afterPlaying = playing
                ?.let { p -> originalQueue.indexOfFirst { it === p } }
                ?.takeIf { it >= 0 }
                ?.plus(1)
                ?: originalQueue.size
            originalQueue = originalQueue.toMutableList().also { it.addAll(afterPlaying, songs) }
        }
        withController { addMediaItems(insertAt, songs.map { it.toMediaItem() }) }
    }

    /** Moves a queue item from [from] to [to]. */
    fun moveQueueItem(from: Int, to: Int) {
        if (from == to) return
        if (from !in queue.indices || to !in queue.indices) return
        val mutable = queue.toMutableList()
        val item = mutable.removeAt(from)
        mutable.add(to, item)
        queue = mutable
        withController { moveMediaItem(from, to) }
    }

    /** Removes the item at [index] from the queue (cannot remove the current track). */
    fun removeFromQueue(index: Int) {
        if (index == currentIndex || index !in queue.indices) return
        val removed = queue[index]
        queue = queue.toMutableList().also { it.removeAt(index) }
        if (index < currentIndex) currentIndex--
        if (shuffle && originalQueue.isNotEmpty()) {
            val at = originalQueue.indexOfFirst { it === removed }
            if (at >= 0) originalQueue = originalQueue.toMutableList().also { it.removeAt(at) }
        }
        withController { removeMediaItem(index) }
    }

    // ----------------------------------------------------------- transport

    /**
     * Media3's own play/pause logic: pauses while playing or buffering, and on Play
     * re-prepares an errored player or restarts an ended queue — so the button never
     * looks dead at the end of the queue.
     */
    fun togglePlayPause() = withController { Util.handlePlayPauseButtonAction(this) }

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
        SleepTimer.set(minutes)
    }

    // ----------------------------------------------------------- stop / undo

    /** Everything needed to put a cleared queue back, for the mini-player's Undo. */
    class Snapshot internal constructor(
        internal val queue: List<Song>,
        internal val index: Int,
        internal val positionMs: Long,
        internal val shuffle: Boolean,
        internal val originalQueue: List<Song>,
    )

    fun snapshot(): Snapshot? {
        if (queue.isEmpty()) return null
        return Snapshot(
            queue = queue,
            index = (controller?.currentMediaItemIndex ?: currentIndex).coerceIn(0, queue.lastIndex),
            positionMs = controller?.currentPosition ?: positionMs,
            shuffle = shuffle,
            originalQueue = originalQueue,
        )
    }

    /** Puts a [snapshot] back, paused at the same spot. */
    fun restore(snapshot: Snapshot) {
        queue = snapshot.queue
        originalQueue = snapshot.originalQueue
        shuffle = snapshot.shuffle
        currentIndex = snapshot.index
        current = snapshot.queue[snapshot.index]
        positionMs = snapshot.positionMs
        lastMediaId = current?.id?.toString()
        persistShuffleState()
        val items = snapshot.queue.map { it.toMediaItem() }
        withController {
            setMediaItems(items, snapshot.index, snapshot.positionMs)
            prepare()
        }
    }

    /**
     * Fully stops playback and tears the session down: clears the queue and the saved
     * playback state, and stops the player so the media notification is removed. Used
     * when the user swipes the mini-player away.
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
        const val KEY_SHUFFLE = "shuffle_enabled"
        const val KEY_ORIGINAL_ORDER = "shuffle_original_order"

        /** Past this far into a track, Previous restarts it instead of going back (VLC). */
        const val RESTART_THRESHOLD_MS = 5_000L
        const val MAX_HISTORY = 100
    }
}

/**
 * [forceReload] gives the item a fresh cache key, which makes ExoPlayer treat it as a new
 * source and re-open the file — needed after the file was rewritten in place.
 */
private fun Song.toMediaItem(forceReload: Boolean = false): MediaItem =
    MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(uri)
        .apply { if (forceReload) setCustomCacheKey("$uri#${System.nanoTime()}") }
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .build(),
        )
        .build()

/** The library's song for a queued item, or a display-only stand-in built from the item. */
private fun MediaItem.toSong(
    library: Map<Long, Song>,
    saved: Map<Long, PlaybackStatePreferences.Item>,
): Song {
    val id = mediaId.toLongOrNull() ?: 0L
    return library[id] ?: Song(
        id = id,
        uri = localConfiguration?.uri?.toString() ?: saved[id]?.uri.orEmpty(),
        title = mediaMetadata.title?.toString().orEmpty(),
        artist = mediaMetadata.artist?.toString(),
        album = mediaMetadata.albumTitle?.toString(),
    )
}

@Composable
fun rememberPlayerState(): PlayerState {
    val context = LocalContext.current
    val state = remember { PlayerState(context.applicationContext) }
    DisposableEffect(Unit) {
        onDispose { state.release() }
    }
    return state
}
