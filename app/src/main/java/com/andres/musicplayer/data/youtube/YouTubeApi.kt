package com.andres.musicplayer.data.youtube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A playlist as the YouTube Data API describes it, before it is stored locally. */
data class RemotePlaylist(
    val id: String,
    val title: String,
    val thumbnailUrl: String,
    val itemCount: Int,
)

/** One video inside a playlist, as returned by `playlistItems`. */
data class RemoteVideo(
    val videoId: String,
    val title: String,
    val thumbnailUrl: String,
)

/** The API answered, but not with success — [message] is safe to show to the user. */
open class YouTubeApiException(message: String) : IOException(message)

/** HTTP 401: the access token is expired or was revoked. Re-authorize and retry once. */
class YouTubeUnauthorizedException(message: String) : YouTubeApiException(message)

/**
 * Thin read-only client for the YouTube Data API v3.
 *
 * Deliberately hand-rolled on OkHttp + `org.json` rather than pulling in the Google API
 * client library: the app needs two GET endpoints, and the official client drags in a
 * large transitive tree for no benefit here.
 */
class YouTubeApi(
    private val http: OkHttpClient = defaultClient(),
    /** Overridden only by tests, which point it at a local server. */
    private val baseUrl: String = BASE,
) {

    /**
     * Every playlist the connected account owns, following `nextPageToken` until the
     * account's playlists are exhausted.
     *
     * Note this returns *owned* playlists only. YouTube's system lists (Watch Later,
     * Liked Videos) are not returned by `mine=true` and so never appear in the picker.
     */
    suspend fun listMyPlaylists(accessToken: String): List<RemotePlaylist> {
        val playlists = mutableListOf<RemotePlaylist>()
        var pageToken: String? = null
        do {
            val url = buildString {
                append("$baseUrl/playlists?part=snippet,contentDetails&mine=true&maxResults=50")
                if (pageToken != null) append("&pageToken=$pageToken")
            }
            val page = get(url, accessToken)
            val items = page.optJSONArray("items")
            for (i in 0 until (items?.length() ?: 0)) {
                parsePlaylist(items!!.getJSONObject(i))?.let(playlists::add)
            }
            pageToken = page.optString("nextPageToken").ifEmpty { null }
            // Guard against a malformed response paging forever.
        } while (pageToken != null && playlists.size < MAX_PLAYLISTS)
        return playlists
    }

    /**
     * Every video in one playlist, following `nextPageToken` to the end.
     *
     * Videos that have been deleted or made private still occupy a slot in the playlist
     * but can't be downloaded, so they're filtered out here rather than being queued and
     * failing later. `status.privacyStatus` is the reliable signal — the title of such an
     * entry is a localised string like "Deleted video", which isn't safe to match on.
     */
    suspend fun listPlaylistItems(
        accessToken: String,
        playlistId: String,
    ): List<RemoteVideo> {
        val videos = mutableListOf<RemoteVideo>()
        var pageToken: String? = null
        do {
            val url = buildString {
                append("$baseUrl/playlistItems?part=snippet,contentDetails,status")
                append("&playlistId=$playlistId&maxResults=50")
                if (pageToken != null) append("&pageToken=$pageToken")
            }
            val page = get(url, accessToken)
            val items = page.optJSONArray("items")
            for (i in 0 until (items?.length() ?: 0)) {
                parsePlaylistItem(items!!.getJSONObject(i))?.let(videos::add)
            }
            pageToken = page.optString("nextPageToken").ifEmpty { null }
        } while (pageToken != null && videos.size < MAX_PLAYLIST_ITEMS)
        return videos
    }

    /** The connected account's YouTube channel name, used to label the connection. */
    suspend fun myChannelTitle(accessToken: String): String? {
        val body = get("$baseUrl/channels?part=snippet&mine=true&maxResults=1", accessToken)
        val items = body.optJSONArray("items") ?: return null
        if (items.length() == 0) return null
        return items.getJSONObject(0)
            .optJSONObject("snippet")
            ?.optString("title")
            ?.ifEmpty { null }
    }

    private suspend fun get(url: String, accessToken: String): JSONObject =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .build()
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val reason = errorMessage(body) ?: "HTTP ${response.code}"
                    if (response.code == 401) {
                        throw YouTubeUnauthorizedException(reason)
                    }
                    throw YouTubeApiException(reason)
                }
                runCatching { JSONObject(body) }.getOrElse {
                    throw YouTubeApiException("Unexpected response from YouTube.")
                }
            }
        }

    private fun parsePlaylist(item: JSONObject): RemotePlaylist? {
        val id = item.optString("id").ifEmpty { return null }
        val snippet = item.optJSONObject("snippet")
        return RemotePlaylist(
            id = id,
            title = snippet?.optString("title")?.ifEmpty { null } ?: "Untitled playlist",
            thumbnailUrl = bestThumbnail(snippet?.optJSONObject("thumbnails")),
            itemCount = item.optJSONObject("contentDetails")?.optInt("itemCount", 0) ?: 0,
        )
    }

    private fun parsePlaylistItem(item: JSONObject): RemoteVideo? {
        val privacy = item.optJSONObject("status")?.optString("privacyStatus").orEmpty()
        if (privacy in UNAVAILABLE_PRIVACY) return null

        val snippet = item.optJSONObject("snippet")
        val videoId = item.optJSONObject("contentDetails")?.optString("videoId")?.ifEmpty { null }
            ?: snippet?.optJSONObject("resourceId")?.optString("videoId")?.ifEmpty { null }
            ?: return null

        return RemoteVideo(
            videoId = videoId,
            title = snippet?.optString("title")?.ifEmpty { null } ?: videoId,
            thumbnailUrl = bestThumbnail(snippet?.optJSONObject("thumbnails")),
        )
    }

    /** Picks the largest thumbnail that is small enough to be a list row's artwork. */
    private fun bestThumbnail(thumbnails: JSONObject?): String {
        if (thumbnails == null) return ""
        for (size in THUMBNAIL_PREFERENCE) {
            val url = thumbnails.optJSONObject(size)?.optString("url").orEmpty()
            if (url.isNotEmpty()) return url
        }
        return ""
    }

    /** Pulls Google's own `error.message` out of a failure body when there is one. */
    private fun errorMessage(body: String): String? = runCatching {
        JSONObject(body).optJSONObject("error")?.optString("message")?.ifEmpty { null }
    }.getOrNull()

    private companion object {
        const val BASE = "https://www.googleapis.com/youtube/v3"
        val THUMBNAIL_PREFERENCE = listOf("medium", "high", "standard", "default")

        /** A sane ceiling; nobody's account has 2000 playlists, but paging must end. */
        const val MAX_PLAYLISTS = 2000
        const val MAX_PLAYLIST_ITEMS = 5000

        /**
         * A deleted video reports `privacyStatusUnspecified`; a private one reports
         * `private`. Neither can be downloaded by anyone, including the owner.
         */
        val UNAVAILABLE_PRIVACY = setOf("private", "privacyStatusUnspecified")

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
