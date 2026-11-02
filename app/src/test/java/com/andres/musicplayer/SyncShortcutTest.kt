package com.andres.musicplayer

import com.andres.musicplayer.ui.app.Routes
import com.andres.musicplayer.ui.app.bottomSelectedRoute
import com.andres.musicplayer.ui.downloads.playlistCountLabel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The bits of the Downloads-tab sync shortcut that are decisions rather than layout.
 *
 * The playlist picker is now reachable from two tabs. Everything else in the app has one
 * way in, so the "which tab is lit" mapping could be a fixed table; this one route can't.
 */
class SyncShortcutTest {

    @Test
    fun `the picker keeps the tab that opened it lit`() {
        assertEquals(
            Routes.DOWNLOADS,
            bottomSelectedRoute(Routes.SYNCED_PLAYLISTS, Routes.DOWNLOADS),
        )
        assertEquals(
            Routes.SETTINGS,
            bottomSelectedRoute(Routes.SYNCED_PLAYLISTS, Routes.SETTINGS),
        )
    }

    @Test
    fun `the picker falls back to Settings when where it came from is unknown`() {
        // Process death restores the destination without a previous entry to read.
        assertEquals(Routes.SETTINGS, bottomSelectedRoute(Routes.SYNCED_PLAYLISTS, null))
        assertEquals(
            "an unrelated previous route is not a tab to light",
            Routes.SETTINGS,
            bottomSelectedRoute(Routes.SYNCED_PLAYLISTS, Routes.NOW_PLAYING),
        )
    }

    @Test
    fun `every other detail route still maps to its one owning tab`() {
        // Whatever is underneath must not start following the back stack around.
        listOf(Routes.ARTIST_SONGS, Routes.ALBUM_SONGS, Routes.NOW_PLAYING).forEach { route ->
            assertEquals(Routes.AUDIO, bottomSelectedRoute(route, Routes.DOWNLOADS))
        }
        assertEquals(Routes.SETTINGS, bottomSelectedRoute(Routes.EQUALIZER, Routes.DOWNLOADS))
        assertEquals(
            Routes.PLAYLISTS,
            bottomSelectedRoute(Routes.PLAYLIST_DETAIL, Routes.DOWNLOADS),
        )
    }

    @Test
    fun `a bottom-nav route is its own selection`() {
        listOf(
            Routes.AUDIO,
            Routes.BROWSE,
            Routes.PLAYLISTS,
            Routes.DOWNLOADS,
            Routes.SETTINGS,
        ).forEach { route ->
            assertEquals(route, bottomSelectedRoute(route, null))
        }
        assertEquals(Routes.PLAYLISTS, bottomSelectedRoute(null, null))
    }

    @Test
    fun `the sync card counts playlists in words a person would use`() {
        assertEquals("No playlists synced", playlistCountLabel(0))
        assertEquals("Syncing 1 playlist", playlistCountLabel(1))
        assertEquals("Syncing 4 playlists", playlistCountLabel(4))
    }
}
