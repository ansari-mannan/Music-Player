package com.andres.musicplayer.ui.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andres.musicplayer.R
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.andres.musicplayer.data.prefs.FolderPreferences
import com.andres.musicplayer.data.prefs.SettingsState
import com.andres.musicplayer.data.scan.MediaScanner
import com.andres.musicplayer.data.youtube.PlaylistSync
import com.andres.musicplayer.domain.ScanLibraryUseCase
import com.andres.musicplayer.ui.art.ArtworkVersion
import com.andres.musicplayer.ui.audio.AudioScreen
import com.andres.musicplayer.ui.browse.BrowseScreen
import com.andres.musicplayer.App
import com.andres.musicplayer.ui.eq.EqualizerScreen
import com.andres.musicplayer.ui.player.MiniPlayer
import com.andres.musicplayer.ui.player.NowPlayingScreen
import com.andres.musicplayer.ui.player.rememberPlayerState
import com.andres.musicplayer.ui.playlists.PlaylistDetailScreen
import com.andres.musicplayer.ui.playlists.PlaylistsScreen
import com.andres.musicplayer.ui.settings.SettingsScreen
import com.andres.musicplayer.ui.songs.SongsByAlbumScreen
import com.andres.musicplayer.ui.downloads.DownloadsScreen
import com.andres.musicplayer.ui.downloads.rememberDownloadsState
import com.andres.musicplayer.ui.songs.SongsByArtistScreen
import com.andres.musicplayer.ui.youtube.SyncedPlaylistsScreen
import com.andres.musicplayer.ui.youtube.rememberYouTubeSyncState
import kotlinx.coroutines.launch

/**
 * The app's display title ("<name>'s Music Player"), provided from settings so any
 * screen can show the personalised name without plumbing it through every call.
 */
val LocalAppTitle = compositionLocalOf { "Andres' Music Player" }

/** Top-level shell: persistent header, bottom navigation, and mini-player. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(settings: SettingsState) {
    val context = LocalContext.current
    val app = context.applicationContext as App
    val repo = app.repository
    val scanUseCase = remember { ScanLibraryUseCase(MediaScanner(context), repo) }
    val folderPrefs = remember { FolderPreferences(context) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val navController = rememberNavController()
    val player = rememberPlayerState()
    val youtube = rememberYouTubeSyncState()

    var folderUri by remember { mutableStateOf(folderPrefs.watchedFolderUri) }
    var isScanning by remember { mutableStateOf(false) }
    var scanProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    // Ask for notification permission so the media-playback notification can show.
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* playback works regardless of the result */ }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun runScan() {
        val uri = folderUri ?: return
        isScanning = true
        scanProgress = null
        scope.launch {
            val message = try {
                val summary = scanUseCase(Uri.parse(uri)) { scanned, total ->
                    scanProgress = scanned to total
                }
                when {
                    summary.added > 0 || summary.removed > 0 -> {
                        val base = "Added ${summary.added}, removed ${summary.removed} stale entries."
                        if (summary.autoPlaylisted > 0) {
                            "$base ${summary.autoPlaylisted} auto-added to playlists."
                        } else {
                            base
                        }
                    }
                    summary.totalFound == 0 -> "No audio files found in that folder."
                    else -> "No new songs — library is up to date."
                }
            } catch (e: Exception) {
                "Scan failed: ${e.message}"
            } finally {
                isScanning = false
                scanProgress = null
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                // Write too, so we can save edited song info back into the MP3 files.
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            folderPrefs.watchedFolderUri = uri.toString()
            folderUri = uri.toString()
            // Picking a folder immediately scans it — no separate manual scan needed.
            runScan()
        }
    }

    // Auto-scan the watched folder once on startup so newly-downloaded songs (and
    // their subfolder playlists) are picked up without the user opening Settings.
    var autoScanned by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!autoScanned && folderUri != null) {
            autoScanned = true
            runScan()
        }
    }

    // The guaranteed sync trigger: every time the app comes to the foreground, check the
    // watched playlists. Anything new is queued and starts downloading immediately,
    // because the app being open is exactly when starting a foreground service is
    // allowed. PlaylistSync itself rate-limits this so app-switching doesn't hammer it.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                scope.launch {
                    app.playlistSync.syncIfDue(PlaylistSync.APP_OPEN_GAP_MS)
                    // This trigger writes the timestamp straight to SyncPreferences, so
                    // the Downloads tab's card has to be told, or it shows the age of a
                    // sync from two launches ago.
                    youtube.refreshLastSync()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun syncMetadataToFiles() {
        scope.launch {
            val result = repo.syncAllMetadataToFiles()
            // Opus/WebM/FLAC have no tag writer here, so say so rather than quietly
            // reporting a smaller number than the library size.
            val message = if (result.skipped > 0) {
                "Saved song info into ${result.written} files. " +
                    "${result.skipped} skipped (unsupported format or read-only)."
            } else {
                "Saved song info into ${result.written} files."
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    // Surface playback errors (missing file, decode failure, …) as a snackbar.
    LaunchedEffect(player.playbackError) {
        val err = player.playbackError
        if (err != null) {
            snackbarHostState.showSnackbar(err)
            player.clearPlaybackError()
        }
    }

    // Same for YouTube connection / playlist-refresh failures, which can be raised
    // from either Settings or the picker screen.
    LaunchedEffect(youtube.error) {
        val err = youtube.error
        if (err != null) {
            snackbarHostState.showSnackbar(err)
            youtube.clearError()
        }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val isNowPlaying = currentRoute == Routes.NOW_PLAYING
    val isDetail = currentRoute == Routes.ARTIST_SONGS ||
        currentRoute == Routes.ALBUM_SONGS ||
        currentRoute == Routes.PLAYLIST_DETAIL ||
        currentRoute == Routes.EQUALIZER ||
        currentRoute == Routes.SYNCED_PLAYLISTS

    Scaffold(
        topBar = {
            if (!isNowPlaying) {
                Column {
                    AppHeader(
                        showBack = isDetail,
                        onBack = { navController.popBackStack() },
                        showScan = folderUri != null && !isDetail,
                        onScan = { runScan() },
                    )
                    if (isScanning) {
                        val progress = scanProgress
                        if (progress != null && progress.second > 0) {
                            LinearProgressIndicator(
                                progress = { progress.first.toFloat() / progress.second },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        },
        bottomBar = {
            if (!isNowPlaying) {
                Column {
                    if (player.current != null) {
                        MiniPlayer(
                            player = player,
                            onClick = { navController.navigate(Routes.NOW_PLAYING) },
                            onDismiss = {
                                // Same as swiping the media notification away: stop playback
                                // and clear it (the notification + mini-player disappear). The
                                // app itself stays open.
                                player.stopAndClear()
                            },
                        )
                    }
                    AppBottomBar(
                        currentRoute = currentRoute,
                        navController = navController,
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.PLAYLISTS,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.AUDIO) {
                AudioScreen(
                    repo = repo,
                    player = player,
                    folderUri = folderUri,
                    onPickFolder = { folderPicker.launch(null) },
                    onArtistClick = { name ->
                        navController.navigate(Routes.artistSongs(Uri.encode(name)))
                    },
                    onAlbumClick = { name ->
                        navController.navigate(Routes.albumSongs(Uri.encode(name)))
                    },
                    onOpenPlaylist = { id -> navController.navigate(Routes.playlistDetail(id)) },
                )
            }
            composable(Routes.BROWSE) { BrowseScreen(player = player, repo = repo) }
            composable(Routes.PLAYLISTS) {
                PlaylistsScreen(
                    repo = repo,
                    onOpenPlaylist = { id -> navController.navigate(Routes.playlistDetail(id)) },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    settings = settings,
                    folderUri = folderUri,
                    youtube = youtube,
                    onPickFolder = { folderPicker.launch(null) },
                    onScan = { runScan() },
                    onOpenEqualizer = { navController.navigate(Routes.EQUALIZER) },
                    onSyncMetadata = { syncMetadataToFiles() },
                    onManageSyncedPlaylists = {
                        navController.navigate(Routes.SYNCED_PLAYLISTS)
                    },
                    onOpenDownloads = { navController.switchTab(Routes.DOWNLOADS) },
                )
            }
            composable(Routes.EQUALIZER) { EqualizerScreen() }
            composable(Routes.SYNCED_PLAYLISTS) { SyncedPlaylistsScreen(youtube = youtube) }
            composable(Routes.DOWNLOADS) {
                DownloadsScreen(
                    downloads = rememberDownloadsState(),
                    youtube = youtube,
                    onManagePlaylists = { navController.navigate(Routes.SYNCED_PLAYLISTS) },
                )
            }
            composable(
                route = Routes.ARTIST_SONGS,
                arguments = listOf(navArgument("name") { type = NavType.StringType }),
            ) { entry ->
                val name = Uri.decode(entry.arguments?.getString("name") ?: "")
                SongsByArtistScreen(repo = repo, player = player, artist = name)
            }
            composable(
                route = Routes.ALBUM_SONGS,
                arguments = listOf(navArgument("name") { type = NavType.StringType }),
            ) { entry ->
                val name = Uri.decode(entry.arguments?.getString("name") ?: "")
                SongsByAlbumScreen(repo = repo, player = player, album = name)
            }
            composable(
                route = Routes.PLAYLIST_DETAIL,
                arguments = listOf(navArgument("id") { type = NavType.LongType }),
            ) { entry ->
                val id = entry.arguments?.getLong("id") ?: 0L
                PlaylistDetailScreen(repo = repo, player = player, playlistId = id)
            }
            composable(Routes.NOW_PLAYING) {
                NowPlayingScreen(
                    player = player,
                    onBack = { navController.popBackStack() },
                    onSaveInfo = { id, title, artist, album ->
                        scope.launch {
                            repo.updateSongInfo(id, title, artist, album)
                            repo.writeMetadataToFile(id)
                        }
                    },
                    onChangeArtwork = { id, imageUri ->
                        scope.launch {
                            if (repo.writeArtworkFromUri(id, imageUri)) ArtworkVersion.bump()
                        }
                    },
                )
            }
        }
    }

}

/** Persistent header: logo space + app name, with optional back and scan actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppHeader(
    showBack: Boolean,
    onBack: () -> Unit,
    showScan: Boolean,
    onScan: () -> Unit,
) {
    TopAppBar(
        navigationIcon = {
            if (showBack) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            } else {
                // Brand waveform mark, tinted with the accent so it adapts to every theme.
                Image(
                    painter = painterResource(R.drawable.logo_wave),
                    contentDescription = LocalAppTitle.current,
                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .size(width = 44.dp, height = 32.dp),
                )
            }
        },
        title = {
            Text(
                text = LocalAppTitle.current,
                fontWeight = FontWeight.SemiBold,
            )
        },
        actions = {
            if (showScan) {
                IconButton(onClick = onScan) {
                    Icon(Icons.Default.Refresh, contentDescription = "Scan library")
                }
            }
        },
    )
}

@Composable
private fun AppBottomBar(
    currentRoute: String?,
    navController: NavController,
) {
    val selected = bottomSelectedRoute(
        currentRoute,
        navController.previousBackStackEntry?.destination?.route,
    )

    fun navTo(route: String) {
        if (selected != route) navController.switchTab(route)
    }

    // One Row of equal cells, all vertically centered on the same line. (The center slot
    // used to hold a raised "Generate mix" button; mood mixing is gone, so the
    // destinations simply divide the width evenly.)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(72.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            bottomDestinations.forEach { dest -> NavCell(dest, selected, ::navTo) }
        }
    }
}

@Composable
private fun RowScope.NavCell(
    dest: BottomDestination,
    selected: String,
    navTo: (String) -> Unit,
) {
    val isSelected = selected == dest.route
    val color = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .clickable { navTo(dest.route) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            dest.icon,
            contentDescription = dest.label,
            tint = color,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.height(2.dp))
        // Five cells leave roughly 70dp each on a normal phone, which "Downloads" and
        // "Playlists" both fit. Pinned to one line so a large system font size clips a
        // label rather than reflowing it onto two rows and unbalancing the bar.
        Text(
            text = dest.label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

/**
 * Maps detail routes back to the bottom tab they belong under.
 *
 * The playlist picker is the one route with two ways in — Settings, and the Downloads
 * tab's sync card — so it keeps whichever tab actually opened it lit rather than always
 * claiming to be under Settings and making the bar jump on the way in.
 */
internal fun bottomSelectedRoute(currentRoute: String?, previousRoute: String?): String =
    when (currentRoute) {
        Routes.ARTIST_SONGS, Routes.ALBUM_SONGS, Routes.NOW_PLAYING -> Routes.AUDIO
        Routes.EQUALIZER -> Routes.SETTINGS
        Routes.SYNCED_PLAYLISTS ->
            previousRoute?.takeIf { it == Routes.DOWNLOADS } ?: Routes.SETTINGS
        Routes.PLAYLIST_DETAIL -> Routes.PLAYLISTS
        null -> Routes.PLAYLISTS
        else -> currentRoute
    }

/**
 * Switches bottom-nav tabs. Shared with the shortcut in Settings so that arriving at
 * Downloads from there behaves identically to tapping its tab — one entry on the back
 * stack, not a Settings → Downloads pair that Back has to walk out of.
 */
private fun NavController.switchTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id)
        launchSingleTop = true
    }
}
