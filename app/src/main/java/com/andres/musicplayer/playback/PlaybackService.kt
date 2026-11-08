package com.andres.musicplayer.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.andres.musicplayer.MainActivity
import com.andres.musicplayer.data.prefs.PlaybackStatePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Hosts the ExoPlayer + MediaSession so playback continues in the background
 * with a system media notification. The equalizer / bass boost / virtualizer are
 * attached to the audio session here (see [AudioEffects]).
 *
 * Also owns the things that must keep working with no UI around: the sleep timer,
 * skipping past files that can't be played, and remembering repeat / speed.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var playbackPrefs: PlaybackStatePreferences
    private var sleepJob: Job? = null

    /** Consecutive unplayable items; stops the skip-ahead from looping a dead queue forever. */
    private var consecutiveErrors = 0

    override fun onCreate() {
        super.onCreate()
        playbackPrefs = PlaybackStatePreferences(this)
        PlaybackPrefs.init(this)
        SleepTimer.init(this)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        // Equalizer, bass boost, virtualizer, reverb and mono all run inside our own audio
        // pipeline (InAppEffectsProcessor), so they sound the same on every phone and need
        // no special permission. Android's versions of these depend on the phone maker.
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)
                .setAudioProcessors(arrayOf(InAppEffectsProcessor()))
                .build()
        }

        val player = ExoPlayer.Builder(this, renderers)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        // Shuffle is implemented by physically reordering the queue (see PlayerState),
        // so ExoPlayer's own hidden shuffle order must stay off permanently.
        player.shuffleModeEnabled = false
        player.repeatMode = PlaybackPrefs.repeatMode
        player.playbackParameters = PlaybackParameters(PlaybackPrefs.speed)

        // Force a stable audio session id up front so the effects can attach
        // before any playback (ExoPlayer otherwise allocates it lazily).
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val sessionId = audioManager.generateAudioSessionId()
        if (sessionId != AudioManager.ERROR) {
            player.setAudioSessionId(sessionId)
        }

        // Restore the queue + position saved before the last process death (paused).
        restorePlaybackState(player)

        // The queue is saved whole only when it changes; everything else just moves the
        // saved position. The tick runs only while playing — a paused player's position
        // can't drift, and the pause itself was already saved.
        player.addListener(object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) = savePlaybackState(player)
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = savePosition(player)
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                savePosition(player)
                if (isPlaying) consecutiveErrors = 0
            }
            override fun onRepeatModeChanged(repeatMode: Int) {
                PlaybackPrefs.repeatMode = repeatMode
            }
            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                PlaybackPrefs.speed = playbackParameters.speed
            }
            override fun onPlayerError(error: PlaybackException) = skipUnplayable(player)
        })
        serviceScope.launch {
            while (isActive) {
                delay(SAVE_INTERVAL_MS)
                if (player.isPlaying) savePosition(player)
            }
        }

        // The sleep timer is enforced here, so it fires even with the app swiped away.
        serviceScope.launch {
            SleepTimer.endElapsedMs.collect { end ->
                sleepJob?.cancel()
                if (end != null) {
                    sleepJob = launch {
                        delay((end - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
                        player.pause()
                        SleepTimer.cancel()
                    }
                }
            }
        }

        // Tapping the notification (or lock-screen controls) opens the player screen.
        val openPlayer = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_PLAYER, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(openPlayer)
            .build()
    }

    /**
     * A missing or undecodable file used to stop the whole queue. Now it's skipped and the
     * next track plays, unless every item in a row has failed (then playback stops, and the
     * UI shows the error).
     */
    private fun skipUnplayable(player: ExoPlayer) {
        consecutiveErrors++
        if (consecutiveErrors >= player.mediaItemCount.coerceAtLeast(1)) return
        if (!player.hasNextMediaItem() && player.repeatMode == Player.REPEAT_MODE_OFF) return
        val wasPlaying = player.playWhenReady
        player.seekToNextMediaItem()
        player.prepare()
        player.playWhenReady = wasPlaying
    }

    private fun restorePlaybackState(player: ExoPlayer) {
        val state = playbackPrefs.load() ?: return
        val items = state.items.map { item ->
            MediaItem.Builder()
                .setMediaId(item.id.toString())
                .setUri(item.uri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(item.title)
                        .setArtist(item.artist)
                        .build(),
                )
                .build()
        }
        if (items.isEmpty()) return
        val index = state.index.coerceIn(0, items.lastIndex)
        runCatching {
            player.setMediaItems(items, index, state.positionMs.coerceAtLeast(0L))
            // Always come back paused-and-ready, never auto-playing on reopen.
            player.playWhenReady = false
            player.prepare()
        }
    }

    private fun savePosition(player: ExoPlayer) {
        if (player.mediaItemCount == 0) return
        playbackPrefs.savePosition(
            index = player.currentMediaItemIndex.coerceAtLeast(0),
            positionMs = player.currentPosition.coerceAtLeast(0L),
        )
    }

    private fun savePlaybackState(player: ExoPlayer) {
        if (player.mediaItemCount == 0) {
            playbackPrefs.clear()
            return
        }
        val items = (0 until player.mediaItemCount).mapNotNull { i ->
            val mediaItem = player.getMediaItemAt(i)
            val uri = mediaItem.localConfiguration?.uri?.toString() ?: return@mapNotNull null
            PlaybackStatePreferences.Item(
                id = mediaItem.mediaId.toLongOrNull() ?: 0L,
                uri = uri,
                title = mediaItem.mediaMetadata.title?.toString() ?: "",
                artist = mediaItem.mediaMetadata.artist?.toString(),
            )
        }
        if (items.isEmpty()) return
        playbackPrefs.save(
            PlaybackStatePreferences.State(
                items = items,
                index = player.currentMediaItemIndex.coerceAtLeast(0),
                positionMs = player.currentPosition.coerceAtLeast(0L),
                wasPlaying = player.isPlaying,
            ),
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        // VLC/Spotify behaviour: if music is playing when the app is swiped away,
        // keep the foreground service (and notification) alive. If it's paused,
        // there's nothing to keep around, so clean up.
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady) {
            pauseAllPlayersAndStopSelf()
        }
    }

    override fun onDestroy() {
        // Capture a final snapshot before tearing down, then stop the ticker.
        mediaSession?.player?.let { if (it is ExoPlayer) savePlaybackState(it) }
        serviceScope.cancel()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    private companion object {
        const val SAVE_INTERVAL_MS = 5_000L
    }
}
