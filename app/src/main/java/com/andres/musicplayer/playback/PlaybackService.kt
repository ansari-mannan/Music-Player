package com.andres.musicplayer.playback

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.andres.musicplayer.data.prefs.PlaybackStatePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Hosts the ExoPlayer + MediaSession so playback continues in the background
 * with a system media notification. The equalizer / bass boost / virtualizer are
 * attached to the audio session here (see [AudioEffects]).
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var playbackPrefs: PlaybackStatePreferences

    override fun onCreate() {
        super.onCreate()
        playbackPrefs = PlaybackStatePreferences(this)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        // Shuffle is implemented by physically reordering the queue (see PlayerState),
        // so ExoPlayer's own hidden shuffle order must stay off permanently.
        player.shuffleModeEnabled = false

        // Force a stable audio session id up front so the effects can attach
        // before any playback (ExoPlayer otherwise allocates it lazily).
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val sessionId = audioManager.generateAudioSessionId()
        if (sessionId != AudioManager.ERROR) {
            player.setAudioSessionId(sessionId)
            AudioEffects.ensureEffects(sessionId)
        }

        // Restore the queue + position saved before the last process death (paused).
        restorePlaybackState(player)

        // Persist on meaningful changes, plus a periodic tick so abrupt death loses
        // at most a few seconds of position.
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = savePlaybackState(player)
            override fun onIsPlayingChanged(isPlaying: Boolean) = savePlaybackState(player)
        })
        serviceScope.launch {
            while (isActive) {
                delay(SAVE_INTERVAL_MS)
                savePlaybackState(player)
            }
        }

        mediaSession = MediaSession.Builder(this, player).build()
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
        AudioEffects.release()
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
