# Andres' Music Player

An Android music player for the songs you collect from YouTube. Point it at a music
folder, connect your YouTube account, pick which playlists to watch, and new songs on
them download, tag themselves and show up in a matching playlist on your phone.

> Works on Android 7.0 (Nougat) and newer.

---

## How it fits together

1. **You like a song on YouTube** and add it to one of your playlists — or tap
   **Share → Download to music** to grab just that one.
2. **The app checks your watched playlists** whenever you open it, and in the background
   on a schedule you choose. New videos are queued and downloaded as audio (M4A).
3. **Each download is tagged** — title, artist, album and cover art, using YouTube
   Music's metadata where the song has it — and dropped into your music folder.
4. **It lands in your library straight away**, inside an in-app playlist named after the
   YouTube playlist it came from. No file manager, no manual sorting.

---

## Getting started

1. Install the APK and open it.
2. **Settings → Music folder → Choose folder.** The app scans it and builds your library.
   Subfolders become playlists automatically.
3. **Settings → YouTube sync → Connect YouTube account**, then **Manage synced
   playlists** and switch on the ones to watch.
4. Optional: **Download on Wi-Fi only**, how many downloads run at once, and how often to
   check in the background.

---

## Features

### Library
- **Audio** tab: Tracks, Artists, Albums and Playlists.
- **Search and sort** the track list (recently added, title, artist, album, length),
  with a fast-scroll thumb for long lists.
- Every song has a **⋮ menu**: Play next, Add to queue, Add to playlist, Edit info,
  Trim / cut, Remove. Long-press to select several songs at once.
- **Browse** tab: every audio file on the device by folder, playable directly.
- **Removing a song** only hides it from the library — the file stays, and it won't come
  back on the next scan. **Settings → Hidden songs** brings them back.
- Moving or re-picking the folder keeps your playlists: the scan recognises the same
  file under its new location.

### Trim / cut
For live recordings with crowd noise, announcements or chatter:
- Drag two handles over the song's waveform, or tap the waveform to listen from a point
  and press **Start here / End here**. Fine-tune with ±0.1 s and ±1 s.
- **Keep selection** chops off the start and end; **Cut selection** removes a chunk from
  the middle. Preview before saving.
- The edit is written into the file (AAC, high quality, with short fades so cuts don't
  click). Title, artist, album, cover and playlists are kept. The original is kept too,
  so **Undo last trim** puts it back.

### Downloads
- Active / Failed / Completed tabs, pause / resume / cancel, retry, and live progress.
- A cancelled download stays cancelled — the next sync won't queue it again.
- Background checks download what they find, not just queue it.
- Paste a link in the Downloads tab, or share one from the YouTube app.

### Playback
- Mini-player above the bottom bar (swipe it away to stop — with **Undo**), and a full
  Now Playing screen (swipe down to close).
- Shuffle without a gap, repeat (off / all / one), speed, sleep timer — the timer works
  even with the app swiped away.
- **Up next** queue: jump, remove, drag to reorder.
- A missing or broken file is skipped instead of stopping the queue.
- Background playback with lock-screen and notification controls; tapping the
  notification opens Now Playing.
- Resumes where you left off, including shuffle, repeat and speed.
- **Equalizer**, bass boost and virtualizer, saved and re-applied automatically.
- **Mono audio** for one-earbud listening, inside the app (no special setup).

### Your edits travel with the files
Edit info and artwork are written into the files themselves (MP3 and M4A), so they
survive a reinstall. Writes are crash-safe: if the app dies mid-write, the original file
is put back on the next launch.

### Make it yours
Your name in the title, light / dark / system theme, accent colours (including
**Dynamic** on Android 12+), and a set of full designed themes.

---

## Building

```
./gradlew assembleRelease
```

Release builds are shrunk with R8 (rules in `app/proguard-rules.pro`). Install updates
with `adb install -r`, signed with the same key as the installed app — a differently
signed build forces an uninstall, which wipes the library, playlists and settings.
