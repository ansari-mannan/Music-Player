package com.andres.musicplayer.ui.youtube

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.andres.musicplayer.App
import com.andres.musicplayer.data.local.entity.WatchedPlaylist
import com.andres.musicplayer.data.prefs.SyncPreferences
import com.andres.musicplayer.data.youtube.NotConnectedException
import com.andres.musicplayer.data.youtube.PlaylistSync
import com.andres.musicplayer.data.youtube.YouTubeApiException
import com.andres.musicplayer.data.youtube.YouTubeAuth
import com.andres.musicplayer.download.DownloadService
import com.andres.musicplayer.download.PlaylistSyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Compose-facing state for the YouTube connection and the playlist picker: connection
 * status, the mirrored playlist list, and the connect / refresh / toggle actions.
 *
 * Created once at the app shell and shared, because Settings shows the connection
 * status while the picker screen edits it — two views of the same thing.
 */
@Stable
class YouTubeSyncState(context: Context) {

    private val app = context.applicationContext as App
    private val repo = app.youTubeRepository
    private val syncPrefs = SyncPreferences(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Shows the Google consent screen; wired up by [rememberYouTubeSyncState]. */
    internal var launchConsent: ((IntentSender) -> Unit)? = null

    var isConnected by mutableStateOf(repo.isConnected)
        private set

    /** Email (or channel name) of the connected account, once known. */
    var accountLabel by mutableStateOf(repo.accountLabel)
        private set

    var playlists by mutableStateOf<List<WatchedPlaylist>>(emptyList())
        private set

    /** True while connecting or refreshing — drives the progress indicator. */
    var isWorking by mutableStateOf(false)
        private set

    /** True once a refresh has completed, so "no playlists" isn't shown prematurely. */
    var hasLoaded by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    /** How many playlists are actually being watched — the headline on the Downloads card. */
    val watchedCount: Int get() = playlists.count { it.isWatched }

    /** Epoch millis of the last completed sync pass, or 0 if one never has. */
    var lastSyncAt by mutableStateOf(syncPrefs.lastSyncAt)
        private set

    /** What that pass did, in one sentence. */
    var lastSyncSummary by mutableStateOf(syncPrefs.lastSyncSummary)
        private set

    /**
     * Set when [checkNow] declined to run — shown inline rather than as an error, because
     * "you already checked ten seconds ago" is not something going wrong.
     */
    var syncNotice by mutableStateOf<String?>(null)
        private set

    init {
        scope.launch { repo.playlists.collect { playlists = it } }
    }

    /**
     * Checks every watched playlist for new songs right now, and starts downloading
     * anything it finds — the same two steps [PlaylistSyncWorker] takes when Android gets
     * round to running it, which on a throttled device can be a long wait.
     *
     * Rate-limited exactly like the app-open trigger so the button can't be leaned on.
     * A refused pass says so in [syncNotice] rather than pretending to have run.
     */
    fun checkNow() {
        if (isWorking) return
        isWorking = true
        error = null
        syncNotice = null
        scope.launch {
            try {
                val result = app.playlistSync.syncIfDue(PlaylistSync.APP_OPEN_GAP_MS)
                if (result == null) {
                    syncNotice = "Checked less than a minute ago."
                } else {
                    // Queued rows only start while the app counts as foreground, and a
                    // button press is exactly that.
                    DownloadService.ensureRunning(app)
                }
                readLastSync()
            } catch (e: Exception) {
                error = describe(e)
            } finally {
                isWorking = false
            }
        }
    }

    fun clearSyncNotice() {
        syncNotice = null
    }

    /**
     * Re-reads when the last sync happened. Called after the app-open trigger fires, which
     * writes the timestamp straight to [SyncPreferences] without going through this class
     * — without this the Downloads card would keep showing the age of whichever sync
     * happened before the screen was built.
     */
    fun refreshLastSync() {
        syncNotice = null
        readLastSync()
    }

    private fun readLastSync() {
        lastSyncAt = syncPrefs.lastSyncAt
        lastSyncSummary = syncPrefs.lastSyncSummary
    }

    /**
     * Connects the account, or refreshes an already-connected one. The account picker
     * and consent screen appear only when Google says they're needed — which, after the
     * first successful connection, is never.
     */
    fun connect() {
        if (isWorking) return
        isWorking = true
        error = null
        scope.launch {
            try {
                when (val outcome = repo.auth.authorize()) {
                    is YouTubeAuth.Outcome.Authorized -> {
                        readConnection()
                        refreshNow()
                    }
                    is YouTubeAuth.Outcome.NeedsConsent -> {
                        val launch = launchConsent
                        if (launch == null) {
                            error = "Couldn't open the Google sign-in screen."
                        } else {
                            // The consent activity takes over; picked up in onConsentResult.
                            launch(outcome.intentSender)
                        }
                    }
                }
            } catch (e: Exception) {
                error = describe(e)
            } finally {
                isWorking = false
            }
        }
    }

    /** Handles the result of the Google consent screen launched by [connect]. */
    internal fun onConsentResult(data: Intent?) {
        val token = repo.auth.onConsentResult(data)
        if (token == null) {
            error = "YouTube wasn't connected."
            return
        }
        readConnection()
        refresh()
    }

    /** Re-reads the account's playlists from YouTube. */
    fun refresh() {
        if (isWorking || !isConnected) return
        isWorking = true
        error = null
        scope.launch {
            try {
                refreshNow()
            } catch (e: Exception) {
                error = describe(e)
            } finally {
                isWorking = false
            }
        }
    }

    fun setWatched(playlistId: String, watched: Boolean) {
        scope.launch {
            runCatching { repo.setWatched(playlistId, watched) }
        }
    }

    fun disconnect() {
        scope.launch {
            repo.disconnect()
            readConnection()
            hasLoaded = false
            error = null
        }
    }

    fun clearError() {
        error = null
    }

    fun dispose() = scope.cancel()

    private suspend fun refreshNow() {
        repo.refreshPlaylists()
        readConnection()
        hasLoaded = true
    }

    private fun readConnection() {
        isConnected = repo.isConnected
        accountLabel = repo.accountLabel
    }

    /** Turns an exception into something worth putting in front of a person. */
    private fun describe(e: Exception): String = when (e) {
        is NotConnectedException -> e.message ?: "YouTube account isn't connected."
        is YouTubeApiException -> "YouTube said: ${e.message}"
        is IOException -> "Couldn't reach YouTube — check the connection."
        else -> e.message ?: "Something went wrong talking to YouTube."
    }
}

@Composable
fun rememberYouTubeSyncState(): YouTubeSyncState {
    val context = LocalContext.current
    val state = remember { YouTubeSyncState(context) }
    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        state.onConsentResult(result.data)
    }
    DisposableEffect(state) {
        state.launchConsent = { sender ->
            consentLauncher.launch(IntentSenderRequest.Builder(sender).build())
        }
        onDispose {
            state.launchConsent = null
            state.dispose()
        }
    }
    return state
}
