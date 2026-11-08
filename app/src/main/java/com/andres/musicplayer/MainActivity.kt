package com.andres.musicplayer

import android.content.Intent
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
import kotlinx.coroutines.flow.MutableStateFlow

/** Entry point. Hosts the whole app shell (header, bottom nav, mini-player). */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_PLAYER, false) == true) {
            openPlayerRequest.value = true
            intent.removeExtra(EXTRA_OPEN_PLAYER)
        }
    }

    companion object {
        /** Set by the media notification's tap action: open straight to Now Playing. */
        const val EXTRA_OPEN_PLAYER = "open_player"

        /** Consumed (reset to false) by AppRoot once it has navigated. */
        val openPlayerRequest = MutableStateFlow(false)
    }
}
