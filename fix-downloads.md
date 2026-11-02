# Task: ytdlnis-parity metadata + fix the download-manager bug list

Work in this Android/Kotlin app (package `com.andres.musicplayer`). Read the actual
current code in every file mentioned below before changing anything — don't assume the
descriptions here are still 100% accurate, verify against source. Do each numbered item
as a separate commit-sized change, and after each one run/extend the relevant tests in
`src/test/java/com/andres/musicplayer/`.

Relevant files you'll be touching:
- `download/DownloadEngine.kt`, `download/CoverArt.kt`, `download/TrackTags.kt`,
  `download/PlaylistSyncWorker.kt`, `download/DownloadService.kt`
- `data/youtube/StreamResolver.kt`, `data/youtube/PlaylistSync.kt`, `data/youtube/YouTubeApi.kt`
- `data/tags/Mp4Tag.kt`
- `data/prefs/DownloadPreferences.kt`
- `ui/downloads/DownloadsScreen.kt`, `ui/downloads/DownloadsState.kt`
- `ui/youtube/SyncedPlaylistsScreen.kt`, `ui/youtube/YouTubeSyncState.kt`
- `App.kt`
- Tests: `AudioStreamChoiceTest.kt`, `Mp4TagTest.kt`, `TrackTagsTest.kt`, `DownloadUtilsTest.kt`

---

## 1. Real YT-Music-grade metadata (the ytdlnis parity gap)

`TrackTags.deriveTags()` in `download/TrackTags.kt` only has the video title, channel
name, and playlist title to work with, because `StreamResolver` only calls
NewPipeExtractor's general video info endpoint. ytdlnis gets real `artist`/`album`/`track`
fields because it wraps yt-dlp, which reads YouTube Music's internal metadata layer for
recognized releases.

Implement this **without bundling a Python runtime** (Chaquopy was deliberately rejected
before, and it costs ~15-25MB per ABI plus a slower cold start — don't reopen that
decision unless step 1c below turns out to be too fragile in practice):

1. Add `data/youtube/MusicMetadataResolver.kt`: a small OkHttp client that calls the
   `music.youtube.com` InnerTube `player` endpoint directly (the same one yt-dlp's
   YouTube Music extractor uses) with a `WEB_REMIX` client context, keyed by video id.
   Parse out `videoDetails` / `musicVideoType` and, where present, structured
   artist/album/track fields from the response — not just title/channel.
2. Return a nullable result type (`MusicMetadata?`) — this is a bonus lookup, never a
   requirement. Any HTTP failure, timeout, unexpected JSON shape, or "this isn't a music
   video" response must be caught and swallowed, exactly like `CoverArtFetcher` does for
   thumbnails. Use a short timeout (3-5s) so a slow/failing lookup never meaningfully
   delays a download.
3. Wire it into `DownloadEngine.embedMetadata()`: call the resolver first; if it returns
   real artist/album, use those; otherwise fall through to the existing
   `deriveTags()` heuristics unchanged. Log which path won (`"Tagged $videoId via
   YT-Music metadata"` vs `"...via heuristics"`) at the same `DownloadEngine` tag used
   today, so this is diagnosable the same way the current tagging is.
4. Add `MusicMetadataResolverTest.kt` mirroring `TrackTagsTest.kt`'s style: feed it
   canned JSON fixtures (a real music-video response, a non-music response, a malformed
   response) rather than hitting the network in tests.
5. Leave the door open: structure this as an interface
   (`MetadataResolver.resolve(videoId): MusicMetadata?`) so a future Chaquopy+yt-dlp
   implementation could be swapped in later without touching `DownloadEngine`.

## 2. Cover art still missing after the m4a-first fix

The container fix (`pickBestAudio` preferring m4a) is real and correct — don't touch it.
The remaining gap is in `download/CoverArt.kt` and how `DownloadEngine.coverJpeg()` uses
it:

1. **Single point of failure on the thumbnail URL.** `StreamResolver.pickBestThumbnail()`
   returns exactly one URL (the single largest thumbnail). If that one request 404s,
   times out, or fails to decode, the track ends up with zero cover art even though a
   smaller thumbnail on the same video would have worked fine. Change
   `StreamResolver` to expose the **ranked list** of thumbnail URLs (not just the top
   pick), and change `CoverArtFetcher`/`coverJpeg` to try them in descending-quality
   order, falling through to the next candidate on any failure (bad HTTP status, decode
   failure, oversize). Only return null once every candidate has failed. Log which
   candidate (by index/resolution) actually succeeded.
2. **Enforce the size cap while streaming, not just from the `Content-Length` header.**
   `download()` in `CoverArt.kt` only checks `body.contentLength() > MAX_DOWNLOAD_BYTES`,
   which does nothing when the server omits or chunks the response (`contentLength()`
   returns `-1`, which is never `>` the cap). Read into a bounded buffer and abort once
   `MAX_DOWNLOAD_BYTES` is exceeded regardless of what the header said.
3. **Stop the Completed list from showing a thumbnail the file doesn't have.** Right
   now `DownloadsScreen`'s completed rows render `entry.thumbnailUrl` (the playlist
   listing image, always present) regardless of whether `embedMetadata()` actually got a
   cover into the file. After a successful embed, persist that fact
   (add a `hasEmbeddedArt: Boolean` column to `DownloadEntry`/`download_entries`, set by
   `DownloadDao` right after `Mp4Tag.coverArtSize()` confirms a readable cover) and have
   the Completed row only show its thumbnail — or show a small "no artwork" badge instead
   — based on that real flag, not on the always-present listing thumbnail. Add a Room
   migration for the new column.
4. Extend `Mp4TagTest.kt` with a round-trip test against a real small `.m4a` fixture
   (add one under `src/test/resources/`, not a synthetically hand-built box tree) that
   writes a cover and reads it back, so a regression in the box-rewriting logic itself
   would be caught, separately from the network-side fallback logic above.

## 3. Pause All / Resume All

Read `DownloadEngine.pauseAll()`/`resumeAll()` and `DownloadsState.pauseAll()`/
`resumeAll()` as they stand today — they transition specific states
(`QUEUED→PAUSED`, `PAUSED→QUEUED`) and are not a `!state` toggle anywhere in this
codebase. Do not "fix" logic that isn't there. Instead:

1. Add `DownloadEngineStateTests.kt` (unit test, fake DAO) asserting that starting from a
   mixed queue (some `PAUSED`, some `QUEUED`, some `DOWNLOADING`) — calling `pauseAll()`
   then `resumeAll()` produces the expected end state, to lock in current behavior and
   catch any future regression.
2. In the PR description / commit message, note explicitly that this was checked against
   a stale-build hypothesis: the reported symptom (buttons acting as a toggle) doesn't
   match anything in `DownloadEngine.kt`, so a clean uninstall + reinstall is the next
   step if it's still reproducible after this change lands.

## 4. Downloads tab has no playlist-sync visibility, and no manual "check now"

1. In `App.kt`, `onCreate()` currently only calls `PlaylistSyncWorker.schedule(this)` —
   it never actually calls `playlistSync.sync()`, despite `PlaylistSync`'s own doc-comment
   claiming to be "shared by the app-open trigger and the periodic worker." Add the real
   app-open trigger: call `app.playlistSync.sync()` from `App.onCreate()` (or from
   `MainActivity` if `Application.onCreate()` is too early for the auth state to be
   ready — check `YouTubeRepository.from(this)`'s init requirements first), gated by a
   minimum interval (e.g. skip if the last sync was under 5 minutes ago — store the
   timestamp in `SyncPreferences`) so it doesn't fire on every cold start.
2. Add a manual "check now" action: a refresh button on `SyncedPlaylistsScreen` (next to
   the existing connect/refresh controls in `YouTubeSyncState`) that calls
   `app.playlistSync.sync()` + `DownloadService.ensureRunning()` — the same two calls
   `PlaylistSyncWorker.doWork()` makes — with a loading state reusing the existing
   `isWorking` flag on `YouTubeSyncState`.
3. Add a compact summary card at the top of `DownloadsScreen.kt`: watched-playlist count,
   last-synced time, and the same "check now" action from (2), with a "Manage" link that
   navigates to `Routes.SYNCED_PLAYLISTS`. Don't move `SyncedPlaylistsScreen` out of
   Settings — just surface a shortcut, since sync feeds the Downloads tab directly.

## 5. Concurrency setting not "reflected" mid-session

`DownloadPreferences.concurrency` is already a `StateFlow` that `DownloadEngine.init`
collects and calls `pump()` on every change, so **raising** the limit mid-session already
pulls more `QUEUED` items in immediately. **Lowering** it is intentionally not force-
pausing already-running downloads (there's a comment saying so). The likely real
complaint is that lowering it looks like nothing happened. Fix the confusion, not the
(correct) behavior:

1. Add a one-line caption under the concurrency picker in Settings:
   "Lowering this won't stop downloads already in progress — it only limits new ones."
2. Add `DownloadEngineConcurrencyTest.kt` asserting `pump()` runs on every emission from
   `downloadPrefs.concurrency`, and that raising the limit while items are `QUEUED`
   starts exactly the newly-available slots.

---

## Acceptance criteria

- Every item above has a passing test named in that section.
- `./gradlew testDebugUnitTest` passes.
- No change to `pickBestAudio`'s m4a-first ordering, `Mp4Tag`'s box-rewrite strategy, or
  the DAO's `LEFT JOIN` playlist-title logic — those are already correct; don't touch them
  beyond what's specified above.
- Every new network call (metadata resolver, thumbnail fallback loop) fails soft: a
  download must never be marked `FAILED` because metadata or cover art enrichment didn't
  work out.
