package com.andres.musicplayer.ui.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import com.andres.musicplayer.R
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
import com.andres.musicplayer.domain.ScanLibraryUseCase
import com.andres.musicplayer.ui.audio.AudioScreen
import com.andres.musicplayer.ui.browse.BrowseScreen
import com.andres.musicplayer.App
import com.andres.musicplayer.ui.eq.EqualizerScreen
import com.andres.musicplayer.ui.mix.MixScreen
import com.andres.musicplayer.ui.player.MiniPlayer
import com.andres.musicplayer.ui.player.NowPlayingScreen
import com.andres.musicplayer.ui.player.rememberPlayerState
import com.andres.musicplayer.ui.playlists.PlaylistDetailScreen
import com.andres.musicplayer.ui.playlists.PlaylistsScreen
import com.andres.musicplayer.ui.settings.SettingsScreen
import com.andres.musicplayer.ui.songs.SongsByAlbumScreen
import com.andres.musicplayer.ui.songs.SongsByArtistScreen
import com.andres.musicplayer.ui.songs.SongsByMoodScreen
import com.andres.musicplayer.ui.tagging.TagUntaggedSheet
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

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                // Write too, so we can save edited info / moods back into the MP3 files.
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            folderPrefs.watchedFolderUri = uri.toString()
            folderUri = uri.toString()
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
                    summary.totalFound == 0 -> "No MP3 files found in that folder."
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

    // Auto-scan the watched folder once on startup so newly-downloaded songs (and
    // their subfolder playlists) are picked up without the user opening Settings.
    var autoScanned by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!autoScanned && folderUri != null) {
            autoScanned = true
            runScan()
        }
    }

    fun syncTagsToFiles() {
        scope.launch {
            val written = repo.syncAllTagsToFiles()
            snackbarHostState.showSnackbar("Saved tags into $written files.")
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

    // Untagged-songs tagging flow: prompt once on startup, re-openable from Settings.
    val songsForTagging by repo.allSongsWithTags.collectAsState(initial = null)
    val untaggedCount = songsForTagging?.count { it.tags.isEmpty() } ?: 0
    var taggingPrompted by remember { mutableStateOf(false) }
    var showTagPrompt by remember { mutableStateOf(false) }
    var showTagFlow by remember { mutableStateOf(false) }
    LaunchedEffect(songsForTagging) {
        val list = songsForTagging
        if (list != null && !taggingPrompted) {
            taggingPrompted = true
            if (list.any { it.tags.isEmpty() }) showTagPrompt = true
        }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val isNowPlaying = currentRoute == Routes.NOW_PLAYING
    val isDetail = currentRoute == Routes.MOOD_SONGS ||
        currentRoute == Routes.ARTIST_SONGS ||
        currentRoute == Routes.ALBUM_SONGS ||
        currentRoute == Routes.PLAYLIST_DETAIL ||
        currentRoute == Routes.EQUALIZER

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
                        )
                    }
                    AppBottomBar(
                        currentRoute = currentRoute,
                        navController = navController,
                        onGenerateMix = {
                            navController.navigate(Routes.MIX) {
                                popUpTo(navController.graph.findStartDestination().id)
                                launchSingleTop = true
                            }
                        },
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
                    onMoodClick = { tagId -> navController.navigate(Routes.moodSongs(tagId)) },
                    onArtistClick = { name ->
                        navController.navigate(Routes.artistSongs(Uri.encode(name)))
                    },
                    onAlbumClick = { name ->
                        navController.navigate(Routes.albumSongs(Uri.encode(name)))
                    },
                    onOpenPlaylist = { id -> navController.navigate(Routes.playlistDetail(id)) },
                    onOpenNowPlaying = { navController.navigate(Routes.NOW_PLAYING) },
                )
            }
            composable(Routes.BROWSE) { BrowseScreen(player = player, repo = repo) }
            composable(Routes.MIX) {
                MixScreen(
                    repo = repo,
                    player = player,
                    onOpenNowPlaying = { navController.navigate(Routes.NOW_PLAYING) },
                )
            }
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
                    onPickFolder = { folderPicker.launch(null) },
                    onScan = { runScan() },
                    onOpenEqualizer = { navController.navigate(Routes.EQUALIZER) },
                    untaggedCount = untaggedCount,
                    onTagUntagged = { showTagFlow = true },
                    onSyncTags = { syncTagsToFiles() },
                )
            }
            composable(Routes.EQUALIZER) { EqualizerScreen() }
            composable(
                route = Routes.MOOD_SONGS,
                arguments = listOf(navArgument("tagId") { type = NavType.LongType }),
            ) { entry ->
                val tagId = entry.arguments?.getLong("tagId") ?: 0L
                SongsByMoodScreen(repo = repo, player = player, tagId = tagId)
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
                )
            }
        }
    }

    if (showTagPrompt) {
        AlertDialog(
            onDismissRequest = { showTagPrompt = false },
            title = { Text("Tag your songs?") },
            text = {
                Text(
                    "You have $untaggedCount untagged " +
                        (if (untaggedCount == 1) "song" else "songs") +
                        ". Add moods now so you can build mixes and browse by vibe. " +
                        "You can stop anytime and finish later.",
                )
            },
            confirmButton = {
                Button(onClick = { showTagPrompt = false; showTagFlow = true }) { Text("Tag now") }
            },
            dismissButton = {
                TextButton(onClick = { showTagPrompt = false }) { Text("Maybe later") }
            },
        )
    }

    if (showTagFlow) {
        TagUntaggedSheet(repo = repo, onDismiss = { showTagFlow = false })
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
    navController: androidx.navigation.NavController,
    onGenerateMix: () -> Unit,
) {
    val selected = bottomSelectedRoute(currentRoute)

    fun navTo(route: String) {
        if (selected != route) {
            navController.navigate(route) {
                popUpTo(navController.graph.findStartDestination().id)
                launchSingleTop = true
            }
        }
    }

    // Bar with the four destinations split around a central slot, plus a raised
    // circular "Generate mix" button that reads as the app's primary action.
    // One Row, five equal cells, all vertically centered on the same line. The
    // center cell holds the circular "Generate mix" button.
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
            NavCell(bottomDestinations[0], selected, ::navTo) // Audio
            NavCell(bottomDestinations[1], selected, ::navTo) // Browse

            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Surface(
                    onClick = onGenerateMix,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(46.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = "Generate mix",
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }

            NavCell(bottomDestinations[2], selected, ::navTo) // Playlists
            NavCell(bottomDestinations[3], selected, ::navTo) // Settings
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
        Text(dest.label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

/** Maps detail routes back to the bottom tab they belong under. */
private fun bottomSelectedRoute(currentRoute: String?): String = when (currentRoute) {
    Routes.MOOD_SONGS, Routes.ARTIST_SONGS, Routes.ALBUM_SONGS, Routes.NOW_PLAYING -> Routes.AUDIO
    Routes.EQUALIZER -> Routes.SETTINGS
    Routes.PLAYLIST_DETAIL -> Routes.PLAYLISTS
    null -> Routes.PLAYLISTS
    else -> currentRoute
}
