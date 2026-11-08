# To do

Work list from the November 2026 review. Ticked items are done in code, compile, and pass
the unit tests; everything still needs a check on the phone.

## 1. Data safety
- [x] A scan that can't read the folder must never wipe the library
- [x] Up next: same song queued twice crashes the sheet
- [x] Restored queue loses album/duration (Edit info wipes the album; seek bar shows --:--)
- [x] Edits to the playing song show up immediately (screen + notification)
- [x] Deleted songs stay deleted (scan no longer re-adds them)
- [x] Tag writes are crash-safe (backup + restore), and don't break the song that's playing

## 2. YouTube sync
- [x] Synced downloads land in a playlist named after their YouTube playlist
- [x] Cancelled / removed downloads stay gone on the next sync
- [x] Background sync actually downloads (not just queues)
- [x] Wi-Fi-only downloads option
- [x] Share a YouTube link from the YouTube app straight into the downloader

## 3. Playback
- [x] Tapping the media notification opens the app
- [x] Sleep timer lives in the playback service (works with the app swiped away)
- [x] Missing/broken file skips to the next song instead of stopping
- [x] Play button works first tap at the end of the queue
- [x] Shuffle on/off without an audio gap
- [x] Play next keeps the order of selected songs
- [x] Repeat mode and speed survive a restart
- [x] Mono audio in-app (no adb, no system setting)

## 4. Friction
- [x] Search and sort on Tracks, plus a fast-scroll thumb
- [x] Rename playlists (long-press menu: Rename / Delete)
- [x] Tabs keep their scroll position when switching
- [x] Back cancels multi-select
- [x] Swiping the mini-player away can be undone
- [x] Song menu always available (not only while something plays), incl. Add to playlist
- [x] Up next: button on Now Playing, opens scrolled to the current song
- [x] Drag handles drag immediately (no long-press)
- [x] No empty-state flash while lists load
- [x] Launch scan is silent unless something changed; "Save song info" shows progress
- [x] Artwork dialog reports the real result; double-tapping the mini-player opens Now Playing once
- [x] Detail screens show their own title in the header

## 5. Speed & upkeep
- [x] R8 on for release builds
- [x] Playlists screen loads counts + 4 covers, not every song
- [x] Changing one cover only reloads that cover
- [x] Equalizer bars only animate while actually playing
- [x] README matches the app

## 6. Trim / cut tool
- [x] Waveform with two handles; Keep selection / Cut selection
- [x] Preview, fine nudge buttons
- [x] Writes the file in place (fades at cuts), keeps tags/cover/playlists, undo via kept original
