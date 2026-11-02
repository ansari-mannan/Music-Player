package com.andres.musicplayer

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.andres.musicplayer.data.SongRepository
import com.andres.musicplayer.data.youtube.PlaylistSync
import com.andres.musicplayer.data.youtube.YouTubeRepository
import com.andres.musicplayer.download.DownloadEngine
import com.andres.musicplayer.download.PlaylistSyncWorker
import com.andres.musicplayer.playback.AudioEffects
import com.andres.musicplayer.ui.art.AudioArt
import com.andres.musicplayer.ui.art.AudioArtFetcher
import com.andres.musicplayer.ui.art.ArtworkVersion

/** Registers the embedded-art fetcher so Coil can load cover art app-wide. */
class App : Application(), SingletonImageLoader.Factory {

    /** One repository (and database) per process, created on first use. */
    val repository: SongRepository by lazy { SongRepository.from(this) }

    /** YouTube account connection + synced-playlist selections. */
    val youTubeRepository: YouTubeRepository by lazy { YouTubeRepository.from(this) }

    /**
     * The download pool. Application-scoped rather than owned by the service, so
     * transfers survive the UI going away and the service can stop and restart around
     * them without interrupting anything.
     */
    val downloadEngine: DownloadEngine by lazy { DownloadEngine.from(this) }

    /**
     * Playlist diffing, shared by the app-open trigger and the periodic worker so both
     * mean exactly the same thing by "sync".
     */
    val playlistSync: PlaylistSync by lazy {
        PlaylistSync.from(this, downloadEngine, youTubeRepository.auth)
    }

    override fun onCreate() {
        super.onCreate()
        // Make saved audio-effect settings available before playback attaches effects.
        AudioEffects.init(this)
        // Before any cover loads: the art cache keys on this persisted counter.
        ArtworkVersion.init(this)
        // Re-apply the periodic sync schedule. Cheap and idempotent — WorkManager keeps
        // the existing schedule when the interval hasn't changed.
        PlaylistSyncWorker.schedule(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(AudioArtFetcher.Factory(this@App), AudioArt::class)
                add(AudioArtFetcher.Keyer(), AudioArt::class)
                // Coil 3 ships no network stack of its own; without this, remote URLs
                // (YouTube playlist thumbnails) silently fail to load.
                add(OkHttpNetworkFetcherFactory())
            }
            .build()
}
