package com.andres.musicplayer.ui.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.andres.musicplayer.App
import com.andres.musicplayer.MainActivity
import com.andres.musicplayer.R
import com.andres.musicplayer.data.prefs.FolderPreferences
import com.andres.musicplayer.data.prefs.SettingsState
import com.andres.musicplayer.data.scan.MediaScanner
import com.andres.musicplayer.data.youtube.PlaylistSync
import com.andres.musicplayer.domain.ScanLibraryUseCase
import com.andres.musicplayer.ui.art.ArtworkVersion
import com.andres.musicplayer.ui.audio.AudioScreen
import com.andres.musicplayer.ui.browse.BrowseScreen
import com.andres.musicplayer.ui.common.LocalSongActions
import com.andres.musicplayer.ui.common.SongActions
import com.andres.musicplayer.ui.downloads.DownloadsScreen
import com.andres.musicplayer.ui.downloads.rememberDownloadsState
import com.andres.musicplayer.ui.eq.EqualizerScreen
import com.andres.musicplayer.ui.player.MiniPlayer
import com.andres.musicplayer.ui.player.NowPlayingScreen
import com.andres.musicplayer.ui.player.rememberPlayerState
import com.andres.musicplayer.ui.playlists.PlaylistDetailScreen
import com.andres.musicplayer.ui.playlists.PlaylistsScreen
import com.andres.musicplayer.ui.settings.SettingsScreen
import com.andres.musicplayer.ui.songs.SongsByAlbumScreen
import com.andres.musicplayer.ui.songs.SongsByArtistScreen
import com.andres.musicplayer.ui.trim.TrimScreen
import com.andres.musicplayer.ui.youtube.SyncedPlaylistsScreen
import com.andres.musicplayer.ui.youtube.rememberYouTubeSyncState
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
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
    /** Progress of "Save song info to files": (done, total), or null when idle. */
    var tagSyncProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    // Ask for notification permission (playback + download notifications) once, not on
    // every launch: rememberSaveable keeps it asked across rotations, and Android itself
    // stops showing the prompt after the user declines twice.
    var askedNotifications by rememberSaveable { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* playback works regardless of the result */ }
    LaunchedEffect(Unit) {
        if (!askedNotifications && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            askedNotifications = true
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /**
     * Scans the music folder. [userAsked] scans always report back; the automatic one on
     * launch stays quiet unless it actually changed something or hit a problem.
     */
    fun runScan(userAsked: Boolean) {
        val uri = folderUri ?: return
        if (isScanning) return
        isScanning = true
        scanProgress = null
        scope.launch {
            val message = try {
                val summary = scanUseCase(Uri.parse(uri)) { scanned, total ->
                    scanProgress = scanned to total
                }
                val parts = buildList {
                    if (summary.added > 0) add("Added ${summary.added}")
                    if (summary.removed > 0) add("removed ${summary.removed} missing")
                    if (summary.moved > 0) add("${summary.moved} moved")
                }
                when {
                    summary.incomplete ->
                        "Part of the music folder couldn't be read, so nothing was removed. " +
                            "Check the folder is still available."
                    parts.isNotEmpty() -> {
                        val base = parts.joinToString(", ").replaceFirstChar { it.uppercase() } + "."
                        if (summary.autoPlaylisted > 0) {
                            "$base ${summary.autoPlaylisted} added to folder playlists."
                        } else {
                            base
                        }
                    }
                    !userAsked -> null
                    summary.totalFound == 0 -> "No audio files found in that folder."
                    else -> "No new songs — library is up to date."
                }
            } catch (e: Exception) {
                "Scan failed: ${e.message}"
            } finally {
                isScanning = false
                scanProgress = null
            }
            if (message != null) snackbarHostState.showSnackbar(message)
        }
    }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                // Write too, so we can save edited song info back into the files.
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            folderPrefs.watchedFolderUri = uri.toString()
            folderUri = uri.toString()
            // Picking a folder immediately scans it — no separate manual scan needed.
            runScan(userAsked = true)
        }
    }

    // On startup: put back any file a crash interrupted mid-write, fix old damaged M4As,
    // then scan the folder so newly-downloaded songs show up without opening Settings.
    var autoScanned by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!autoScanned && folderUri != null) {
            autoScanned = true
            if (repo.recoverInterruptedWrites() > 0) ArtworkVersion.bumpAll()
            if (repo.repairDamagedM4aFilesOnce() > 0) ArtworkVersion.bumpAll()
            runScan(userAsked = false)
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
        if (tagSyncProgress != null) return
        tagSyncProgress = 0 to 0
        scope.launch {
            val result = repo.syncAllMetadataToFiles { done, total -> tagSyncProgress = done to total }
            tagSyncProgress = null
            // Opus/WebM/FLAC have no tag writer here, so say so rather than quietly
            // reporting a smaller number than the library size.
            val message = buildString {
                append("Saved song info into ${result.written} files.")
                if (result.unchanged > 0) append(" ${result.unchanged} already up to date.")
                if (result.skipped > 0) {
                    append(" ${result.skipped} skipped (unsupported format or read-only).")
                }
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

    // Tapping the media notification asks for Now Playing.
    val openPlayer by MainActivity.openPlayerRequest.collectAsState()
    LaunchedEffect(openPlayer, player.current) {
        if (openPlayer && player.current != null) {
            MainActivity.openPlayerRequest.value = false
            navController.navigate(Routes.NOW_PLAYING) { launchSingleTop = true }
        }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val isNowPlaying = currentRoute == Routes.NOW_PLAYING
    val isDetail = currentRoute == Routes.ARTIST_SONGS ||
        currentRoute == Routes.ALBUM_SONGS ||
        currentRoute == Routes.PLAYLIST_DETAIL ||
        currentRoute == Routes.EQUALIZER ||
        currentRoute == Routes.SYNCED_PLAYLISTS ||
        currentRoute == Routes.TRIM

    // What the header says: the app's name on the tabs, the thing you're looking at on a
    // detail screen.
    val playlistId = backStackEntry?.arguments?.getLong("id")
    val playlistTitleFlow = remember(currentRoute, playlistId) {
        if (currentRoute == Routes.PLAYLIST_DETAIL && playlistId != null) {
            repo.playlist(playlistId).map { it?.name }
        } else {
            emptyFlow()
        }
    }
    val playlistTitle by playlistTitleFlow.collectAsState(initial = null)
    val headerTitle = when (currentRoute) {
        Routes.ARTIST_SONGS, Routes.ALBUM_SONGS ->
            Uri.decode(backStackEntry?.arguments?.getString("name") ?: "")
        Routes.PLAYLIST_DETAIL -> playlistTitle ?: "Playlist"
        Routes.EQUALIZER -> "Sound"
        Routes.SYNCED_PLAYLISTS -> "Synced playlists"
        Routes.TRIM -> "Trim / cut"
        else -> LocalAppTitle.current
    }

    val songActions = remember(navController) {
        SongActions(openTrim = { song -> navController.navigate(Routes.trim(song.id)) })
    }

    CompositionLocalProvider(LocalSongActions provides songActions) {
        Scaffold(
            topBar = {
                if (!isNowPlaying) {
                    Column {
                        AppHeader(
                            title = headerTitle,
                            showBack = isDetail,
                            onBack = { navController.popBackStack() },
                            showScan = folderUri != null && !isDetail,
                            onScan = { runScan(userAsked = true) },
                        )
                        val progress = scanProgress ?: tagSyncProgress
                        if (isScanning || tagSyncProgress != null) {
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
                                onClick = {
                                    // launchSingleTop: a double tap opens Now Playing once.
                                    navController.navigate(Routes.NOW_PLAYING) { launchSingleTop = true }
                                },
                                onDismiss = {
                                    // Stops playback and clears the queue, with an Undo in
                                    // case the swipe was an accident.
                                    val snapshot = player.snapshot()
                                    player.stopAndClear()
                                    if (snapshot != null) {
                                        scope.launch {
                                            snackbarHostState.currentSnackbarData?.dismiss()
                                            val result = snackbarHostState.showSnackbar(
                                                message = "Playback stopped",
                                                actionLabel = "Undo",
                                                duration = SnackbarDuration.Short,
                                            )
                                            if (result == SnackbarResult.ActionPerformed) player.restore(snapshot)
                                        }
                                    }
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
                        onPickFolder = { folderPicker.launch(folderPickerStart(folderUri)) },
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
                        onPickFolder = if (folderUri == null) {
                            { folderPicker.launch(folderPickerStart(null)) }
                        } else {
                            null
                        },
                    )
                }
                composable(Routes.SETTINGS) {
                    val hiddenSongs by repo.hiddenSongCount.collectAsState(initial = 0)
                    SettingsScreen(
                        settings = settings,
                        folderUri = folderUri,
                        youtube = youtube,
                        onPickFolder = { folderPicker.launch(folderPickerStart(folderUri)) },
                        onScan = { runScan(userAsked = true) },
                        onOpenEqualizer = { navController.navigate(Routes.EQUALIZER) },
                        onSyncMetadata = { syncMetadataToFiles() },
                        isSyncingMetadata = tagSyncProgress != null,
                        onManageSyncedPlaylists = {
                            navController.navigate(Routes.SYNCED_PLAYLISTS)
                        },
                        onOpenDownloads = { navController.switchTab(Routes.DOWNLOADS) },
                        hiddenSongs = hiddenSongs,
                        onRestoreHiddenSongs = {
                            scope.launch {
                                repo.restoreHiddenSongs()
                                runScan(userAsked = true)
                            }
                        },
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
                composable(
                    route = Routes.TRIM,
                    arguments = listOf(navArgument("id") { type = NavType.LongType }),
                ) { entry ->
                    val id = entry.arguments?.getLong("id") ?: 0L
                    TrimScreen(
                        repo = repo,
                        player = player,
                        songId = id,
                        onDone = { navController.popBackStack() },
                    )
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
                            val uri = player.current?.uri
                            val ok = repo.writeArtworkFromUri(id, imageUri)
                            if (ok && uri != null) ArtworkVersion.bump(uri)
                            ok
                        },
                    )
                }
            }
        }
    }
}

/** Persistent header: logo + app name on the tabs, back + the screen's own name on details. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppHeader(
    title: String,
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
                    contentDescription = null,
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
                text = title,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
        if (selected != route) {
            navController.switchTab(route)
        } else if (currentRoute != route) {
            // Tapping the current tab from one of its detail screens goes back to the tab.
            navController.popBackStack(route, inclusive = false)
        }
    }

    // One Row of equal cells, all vertically centered on the same line.
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
        // Pinned to one line so a large system font size clips a label rather than
        // reflowing it onto two rows and unbalancing the bar.
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
 * claiming to be under Settings and making the bar jump on the way in. The trim editor
 * likewise belongs to whatever opened it.
 */
internal fun bottomSelectedRoute(currentRoute: String?, previousRoute: String?): String =
    when (currentRoute) {
        Routes.ARTIST_SONGS, Routes.ALBUM_SONGS, Routes.NOW_PLAYING -> Routes.AUDIO
        Routes.EQUALIZER -> Routes.SETTINGS
        Routes.SYNCED_PLAYLISTS ->
            previousRoute?.takeIf { it == Routes.DOWNLOADS } ?: Routes.SETTINGS
        Routes.PLAYLIST_DETAIL -> Routes.PLAYLISTS
        Routes.TRIM -> if (previousRoute == null || previousRoute == Routes.TRIM) {
            Routes.AUDIO
        } else {
            bottomSelectedRoute(previousRoute, null)
        }
        null -> Routes.PLAYLISTS
        else -> currentRoute
    }

/**
 * Switches bottom-nav tabs, saving the tab being left and restoring the one being
 * entered — scroll position, sub-tab and any detail screen open inside it — the way
 * every bottom-nav app behaves. One entry on the back stack per switch, so Back doesn't
 * walk through every tab visited.
 */
private fun NavController.switchTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * Where the folder picker opens. Android only lets an app use a folder the user picks in
 * its own picker, so the music folder can't be pre-selected on a fresh install — but the
 * picker can open already *in* the phone's Music folder, leaving just "Use this folder →
 * Allow". When a folder is already chosen (changing it from Settings), it opens there.
 */
private fun folderPickerStart(currentFolder: String?): Uri? {
    if (currentFolder != null) {
        runCatching {
            val tree = Uri.parse(currentFolder)
            return android.provider.DocumentsContract.buildDocumentUriUsingTree(
                tree,
                android.provider.DocumentsContract.getTreeDocumentId(tree),
            )
        }
    }
    return android.provider.DocumentsContract.buildDocumentUri(
        "com.android.externalstorage.documents",
        "primary:" + android.os.Environment.DIRECTORY_MUSIC,
    )
}

