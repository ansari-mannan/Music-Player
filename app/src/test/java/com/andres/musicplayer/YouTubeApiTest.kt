package com.andres.musicplayer

import com.andres.musicplayer.data.youtube.YouTubeApi
import com.andres.musicplayer.data.youtube.YouTubeApiException
import com.andres.musicplayer.data.youtube.YouTubeUnauthorizedException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Exercises the YouTube Data API client against a real local HTTP server, so pagination,
 * filtering and error mapping are checked end-to-end through actual OkHttp and JSON
 * rather than being asserted about in the abstract.
 */
class YouTubeApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: YouTubeApi

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        api = YouTubeApi(
            http = OkHttpClient(),
            baseUrl = server.url("/youtube/v3").toString().trimEnd('/'),
        )
    }

    @After
    fun tearDown() = server.shutdown()

    // --- playlistItems -----------------------------------------------------------------

    @Test
    fun `skips deleted and private videos`() {
        server.enqueue(
            json(
                """
                {"items":[
                  ${item("aaaaaaaaaaa", "Good Song", "public")},
                  ${item("bbbbbbbbbbb", "Private video", "private")},
                  ${item("ccccccccccc", "Deleted video", "privacyStatusUnspecified")},
                  ${item("ddddddddddd", "Also Fine", "unlisted")}
                ]}
                """,
            ),
        )

        val videos = runBlocking { api.listPlaylistItems("token", "PL1") }

        assertEquals(listOf("aaaaaaaaaaa", "ddddddddddd"), videos.map { it.videoId })
        assertEquals(listOf("Good Song", "Also Fine"), videos.map { it.title })
    }

    @Test
    fun `follows pagination to the end`() {
        server.enqueue(
            json(
                """{"nextPageToken":"PAGE2","items":[${item("aaaaaaaaaaa", "One", "public")}]}""",
            ),
        )
        server.enqueue(
            json(
                """{"nextPageToken":"PAGE3","items":[${item("bbbbbbbbbbb", "Two", "public")}]}""",
            ),
        )
        server.enqueue(json("""{"items":[${item("ccccccccccc", "Three", "public")}]}"""))

        val videos = runBlocking { api.listPlaylistItems("token", "PL1") }

        assertEquals(3, videos.size)
        assertEquals(3, server.requestCount)
        // The page token from each response must be carried into the next request.
        server.takeRequest()
        assertTrue(server.takeRequest().path!!.contains("pageToken=PAGE2"))
        assertTrue(server.takeRequest().path!!.contains("pageToken=PAGE3"))
    }

    @Test
    fun `falls back to the snippet resource id when contentDetails is absent`() {
        server.enqueue(
            json(
                """
                {"items":[{
                  "snippet":{"title":"From Snippet","resourceId":{"videoId":"eeeeeeeeeee"}},
                  "status":{"privacyStatus":"public"}
                }]}
                """,
            ),
        )

        val videos = runBlocking { api.listPlaylistItems("token", "PL1") }
        assertEquals("eeeeeeeeeee", videos.single().videoId)
    }

    @Test
    fun `sends the bearer token and the requested playlist`() {
        server.enqueue(json("""{"items":[]}"""))
        runBlocking { api.listPlaylistItems("secret-token", "PLxyz") }

        val request = server.takeRequest()
        assertEquals("Bearer secret-token", request.getHeader("Authorization"))
        assertTrue(request.path!!.contains("playlistId=PLxyz"))
        // status must be requested, or the privacy filter has nothing to read.
        assertTrue(request.path!!.contains("status"))
    }

    // --- playlists ----------------------------------------------------------------------

    @Test
    fun `parses playlists and prefers the medium thumbnail`() {
        server.enqueue(
            json(
                """
                {"items":[{
                  "id":"PL1",
                  "snippet":{"title":"Road Trip","thumbnails":{
                    "default":{"url":"http://img/default.jpg"},
                    "medium":{"url":"http://img/medium.jpg"},
                    "high":{"url":"http://img/high.jpg"}}},
                  "contentDetails":{"itemCount":42}
                }]}
                """,
            ),
        )

        val playlist = runBlocking { api.listMyPlaylists("token") }.single()
        assertEquals("PL1", playlist.id)
        assertEquals("Road Trip", playlist.title)
        assertEquals("http://img/medium.jpg", playlist.thumbnailUrl)
        assertEquals(42, playlist.itemCount)
    }

    @Test
    fun `a playlist with no thumbnail yields an empty url rather than failing`() {
        server.enqueue(json("""{"items":[{"id":"PL1","snippet":{"title":"Empty"}}]}"""))

        val playlist = runBlocking { api.listMyPlaylists("token") }.single()
        assertEquals("", playlist.thumbnailUrl)
        assertEquals(0, playlist.itemCount)
    }

    // --- errors --------------------------------------------------------------------------

    @Test
    fun `401 maps to the unauthorized type so the caller can refresh and retry`() {
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"error":{"message":"Invalid Credentials"}}"""),
        )

        val error = runCatching { runBlocking { api.listMyPlaylists("stale") } }.exceptionOrNull()
        assertTrue(error is YouTubeUnauthorizedException)
        assertEquals("Invalid Credentials", error!!.message)
    }

    @Test
    fun `other errors surface google's own message`() {
        server.enqueue(
            MockResponse().setResponseCode(403)
                .setBody("""{"error":{"message":"YouTube Data API has not been used"}}"""),
        )

        val error = runCatching { runBlocking { api.listMyPlaylists("token") } }.exceptionOrNull()
        assertTrue(error is YouTubeApiException)
        assertTrue(error !is YouTubeUnauthorizedException)
        assertEquals("YouTube Data API has not been used", error!!.message)
    }

    @Test
    fun `an error body with no message still reports the status code`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("upstream exploded"))

        val error = runCatching { runBlocking { api.listMyPlaylists("token") } }.exceptionOrNull()
        assertEquals("HTTP 500", error!!.message)
    }

    @Test
    fun `a success body that is not json is reported rather than crashing`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>captive portal</html>"))

        val error = runCatching { runBlocking { api.listMyPlaylists("token") } }.exceptionOrNull()
        assertTrue(error is YouTubeApiException)
        assertEquals("Unexpected response from YouTube.", error!!.message)
    }

    @Test
    fun `channel title is read for the connection label`() {
        server.enqueue(json("""{"items":[{"snippet":{"title":"Andres' Channel"}}]}"""))
        assertEquals("Andres' Channel", runBlocking { api.myChannelTitle("token") })
    }

    @Test
    fun `channel title is null when the account has no channel`() {
        server.enqueue(json("""{"items":[]}"""))
        assertNull(runBlocking { api.myChannelTitle("token") })
    }

    // --- helpers ---------------------------------------------------------------------------

    private fun json(body: String) =
        MockResponse().setResponseCode(200).setBody(body.trimIndent())

    private fun item(videoId: String, title: String, privacy: String) = """
        {"snippet":{"title":"$title","resourceId":{"videoId":"$videoId"}},
         "contentDetails":{"videoId":"$videoId"},
         "status":{"privacyStatus":"$privacy"}}
    """.trimIndent()
}
