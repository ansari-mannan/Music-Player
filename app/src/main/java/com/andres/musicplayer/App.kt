package com.andres.musicplayer

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.andres.musicplayer.data.SongRepository
import com.andres.musicplayer.playback.AudioEffects
import com.andres.musicplayer.ui.art.AudioArt
import com.andres.musicplayer.ui.art.AudioArtFetcher

/** Registers the embedded-art fetcher so Coil can load cover art app-wide. */
class App : Application(), SingletonImageLoader.Factory {

    /** One repository (and database) per process, created on first use. */
    val repository: SongRepository by lazy { SongRepository.from(this) }

    override fun onCreate() {
        super.onCreate()
        // Make saved audio-effect settings available before playback attaches effects.
        AudioEffects.init(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(AudioArtFetcher.Factory(this@App), AudioArt::class) }
            .build()
}
