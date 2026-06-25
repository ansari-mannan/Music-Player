# Andres' Music Player

A clean, offline music player for Android that plays the MP3s already on your phone, no accounts, no streaming, no ads. Point it at the folder your music lives in and it builds your library, lets you tag songs by **mood**, and blends those moods into fresh **mixes** on demand.

> Works on Android 7.0 (Nougat) and newer.

---

## Why this app exists

I wanted to remove the manual part of building a music library entirely. The old workflow was: hear a song on YouTube, like it, find a downloader, download it, find the right folder, drop it in, then go add it to a playlist by hand. Every song was four or five steps before I could actually listen to it the way I wanted.

So I automated the whole pipeline:

1. **YouTube → playlist.** I like a song, I add it to a specific YouTube playlist (just the "add to playlist" button, nothing else).
2. **YTDLnis watches that playlist.** Using its **Observe Sources** feature, YTDLnis polls the playlist on a schedule. When it sees a new entry, it downloads the audio automatically into a folder I've designated.
3. **This app watches that folder.** Andres' Music Player scans the same folder every time it opens. Any new MP3 that shows up gets pulled into the library automatically, and because of the **auto-playlist** feature, it lands in a playlist named after the subfolder it came from. The YouTube playlist effectively recreates itself on my phone, fully populated, with zero manual file handling.
4. **Mood tagging and mixes happen on top of that.** Once a song is in the library I can tag it with a mood and let the slider-based mix generator blend moods into a new playlist whenever I want a particular vibe, without ever touching a file manager.

End to end: tap "add to playlist" on YouTube, and some time later the song is sitting in the right playlist on my phone, taggable and mixable, without me downloading or moving a single file myself.

---

## Getting started

### Part 1: Install Andres' Music Player

1. **Install the app** (`AndresMusicPlayer-v1.0.apk`) and open it.
2. Go to **Settings → Music folder → Choose folder** and pick the folder your songs will be downloaded into. (You'll point YTDLnis at this exact same folder in Part 2.)
3. The app scans that folder and adds every MP3 to your library.
4. You'll be invited to **tag your songs with moods**, do it now or later. That's what powers mixes and mood browsing.
5. Tap any song to start playing.

That's it for the app itself. From then on, it **auto-scans your folder each time it opens**, so newly downloaded songs show up on their own.

On its own this just gives you a nice offline player. To get the "songs appear automatically after I add them to a YouTube playlist" behaviour, you need Part 2.

### Part 2: Install YTDLnis and set up Observe Sources

YTDLnis is a separate, free, open-source app (not made by me) that downloads audio/video using yt-dlp. It's what actually pulls songs off YouTube. Get it from the project's official channels only:

1. Go to **https://ytdlnis.org** (the project's official site) and open the **Downloads** page, then **Full Releases**, this links through to the project's GitHub Releases page. Don't use random APK mirror sites; stick to the official source so you know the build is unmodified and isn't going to have something nasty injected into it.
2. Pick the right APK for your phone's CPU:
   - **arm64-v8a**: almost every phone made in the last several years (most Android phones today).
   - **armeabi-v7a**: older/budget 32-bit ARM devices.
   - **x86 / x86_64**: Intel-based Android devices or emulators (rare on real phones).
   - **universal**: contains all architectures in one APK. Bigger file size, but it's the safe choice if you're not sure which CPU your phone has, since it works regardless. This is the one to grab if in doubt.
3. Since this isn't from the Play Store, Android will block the install by default. When you tap the APK, you'll be prompted to **allow installs from this source** (the wording varies slightly by Android version, sometimes it's a one-time toggle in a popup, sometimes it's under **Settings → Apps → Special access → Install unknown apps**). Allow it just for the browser or file manager you used to download the APK.
4. Install and open YTDLnis.
5. Inside YTDLnis, go to **Settings → Observe Sources** (this is the feature that does the watching):
   - Tap **Add a source**.
   - Paste the link to the **YouTube playlist** you want watched (create an empty playlist on YouTube first if you don't have one yet; this is the playlist you'll add songs to whenever you like something).
   - Set the **download path** to the *exact same folder* you chose for Andres' Music Player in Part 1. This is the link between the two apps; they're both just looking at the same folder.
   - Make sure the download format is set to **audio only / MP3** (YTDLnis can do video too, but you only want audio for the player).
   - Set how often YTDLnis should check the playlist (e.g. every 15–30 minutes, or on a schedule that suits you).
6. Grant YTDLnis the storage/notification permissions it asks for; it needs these to write files in the background and to show download progress.

### Part 3: Let it run

From here the loop is automatic:

1. You're scrolling YouTube, you hear something you like, you tap **Save → [your watched playlist]**.
2. Next time YTDLnis checks that playlist, it downloads the audio as an MP3 into the shared folder.
3. Next time you open Andres' Music Player, it scans that folder, finds the new file, and adds it to your library, and to its matching auto-playlist if you've organized the folder into subfolders.
4. Tag it with a mood whenever you feel like it, and it's available for mixes immediately.

You never touch a file manager, never manually move a download, and never manually add anything to an in-app playlist. The only manual step in the entire chain is the original "like this song" tap on YouTube.

---

## Features

### Your library, organized
Browse everything you own from the **Audio** tab, split into:
- **Tracks** – every song in your library
- **Artists** – grouped by artist
- **Albums** – grouped by album
- **Moods** – your own mood tags (see below)
- **Mixes** – the mood blender
- **Playlists** – your saved playlists

### Browse the whole device
The **Browse** tab shows *all* the audio on your phone, organized by the folder it lives in, even files outside your main music folder. Play anything directly, or pull a whole folder into a playlist.

### Mood tags
Give your songs moods like *Chill*, *Hype*, *Focus*, *Sad*, whatever you like. You create your own mood names; there's no fixed list.
- A gentle prompt offers to tag any **untagged songs**, one at a time, and you can stop and pick it back up whenever from **Settings → Tag untagged songs**.
- Select several songs at once to tag them all together.
- Browse by mood to instantly pull up everything that fits a vibe.

### Mood mixes
The standout feature. Tap the glowing **center button** to open the mix generator:
- A slider for each mood lets you dial in the blend (e.g. 60% Chill, 40% Focus). The sliders always balance to 100%.
- Choose how many songs you want.
- Hit **Generate mix** and the app builds a shuffled playlist by pulling songs from each mood in the proportions you set.
- **Play it now**, replace your current queue, or **save it as a playlist** to keep.

### Playlists
- Create playlists and add songs from anywhere in the app.
- **Reorder** tracks by long-pressing the drag handle.
- Each playlist gets a nice header with an artwork collage and one-tap **play / shuffle / loop**.
- **Auto-playlists:** if your music folder has subfolders, songs are automatically added to a playlist named after the subfolder they came from. Drop a new album folder in, scan, and it's already a playlist.

### Full-featured playback
- A **mini-player** sits above the navigation bar; tap it for the full **Now Playing** screen with large album art and a blurred-art backdrop.
- Standard controls: play/pause, skip, **scrubbable seek bar**, **shuffle**, and **repeat** (off → repeat all → repeat one).
- **Up next queue** – view it, tap any song to jump to it, remove songs, or **drag to reorder**.
- **Play next / Add to queue** from any song's menu.
- **Playback speed** (0.5× to 2×).
- **Sleep timer** (15 / 30 / 45 / 60 minutes), the remaining time shows on the player.
- **Background playback** with a system media notification, so music keeps going when you leave the app or lock the screen. It also handles headphone unplugging and audio focus politely.
- **Resumes where you left off**, your queue and position survive closing the app (it reopens paused, ready to go).

### Equalizer & audio effects
From **Settings → Equalizer**:
- A **multi-band equalizer** with built-in **presets** (Rock, Pop, Jazz, etc., depending on your device).
- **Bass boost** and a **virtualizer** (stereo widening).
- All settings are saved and re-applied automatically every time you play.

### Edit song info
Fix a wrong title, artist, or album right from the Now Playing screen or any song's menu.

### Tags that survive a reinstall
Your edits and moods can be written **directly into the MP3 files** (via **Settings → Save tags to files**). If you ever reinstall the app or wipe its data, a rescan restores your moods and edited info straight from the files.

### Make it yours
In **Settings → Personalization & Appearance**:
- **Your name** personalizes the app's title (e.g. *"John's Music Player"*).
- **Theme:** follow system, always light, or always dark.
- **Accent colours** – tint the whole app from a single colour, including **Dynamic** which follows your wallpaper (Android 12+).
- **Full designed themes** – I couldn't decide on a single theme so i added a bunch of palettes like *Currents*, *True Black*, *Monochrome*, *Vaporwave*, *Blueprint*, *Acid Terminal*, and more.

---

## A few things to know

- **The app never deletes your audio files.** "Removing" a song or playlist only affects the in-app library; the actual MP3 on your phone stays put.
- It plays **MP3 files** from the folder you choose. The Browse tab can play other audio found on the device too.
- Everything is **on-device and offline**, no internet connection, account, or sign-in required.

---

*Andres' Music Player, version 1.0*
