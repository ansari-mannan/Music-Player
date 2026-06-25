package com.andres.musicplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import com.andres.musicplayer.data.prefs.musicPlayerTitle
import com.andres.musicplayer.data.prefs.rememberSettingsState
import com.andres.musicplayer.ui.app.AppRoot
import com.andres.musicplayer.ui.app.LocalAppTitle
import com.andres.musicplayer.ui.theme.AndresMusicPlayerTheme

/** Entry point. Hosts the whole app shell (header, bottom nav, mini-player). */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings = rememberSettingsState()
            AndresMusicPlayerTheme(
                themeMode = settings.themeMode,
                accentColor = settings.accentColor,
            ) {
                CompositionLocalProvider(
                    LocalAppTitle provides musicPlayerTitle(settings.displayName),
                ) {
                    AppRoot(settings = settings)
                }
            }
        }
    }
}
