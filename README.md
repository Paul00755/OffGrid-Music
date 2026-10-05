# 🎵 OffGrid Music

<p align="center">
  <img src="docs/assets/logo.png" width="140" height="140" alt="OffGrid Music Logo" />
</p>

<p align="center">
  <strong>An offline-first Android music player built for local music, high-quality playback, synchronized lyrics, powerful queue management, and a highly customizable visual experience.</strong>
</p>

<p align="center">
  <a href="../../releases/latest/download/app-debug.apk">
    <img src="https://img.shields.io/badge/Download-Latest%20APK-00E676?style=for-the-badge&logo=android&logoColor=white" alt="Download Latest APK" />
  </a>
  <a href="../../releases">
    <img src="https://img.shields.io/badge/GitHub-Releases-purple?style=for-the-badge&logo=github&logoColor=white" alt="GitHub Releases" />
  </a>
  <a href="../../actions/workflows/build-apk.yml">
    <img src="https://img.shields.io/badge/CI%2FCD-Automated%20Build-blue?style=for-the-badge&logo=githubactions&logoColor=white" alt="Automated Builds" />
  </a>
  <img src="https://img.shields.io/badge/Platform-Android-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Android" />
  <img src="https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white" alt="Kotlin Jetpack Compose" />
</p>

---

## 📖 About

**OffGrid Music** is an offline-first Android music player designed around locally stored music.

It combines a powerful Media3 playback engine with synchronized lyrics, advanced queue controls, dynamic theming, customizable widgets, listening analytics, playlists, cloud synchronization, and extensive audio controls.

The application is designed so that core music playback and library functionality remain available without requiring a network connection.

---

# ✨ Features

## 🎧 Advanced Audio & Playback

### High-quality playback engine

* AndroidX Media3 / ExoPlayer based playback
* Background playback through `MediaSessionService`
* Lock-screen and notification playback controls
* Queue and Up-Next management
* Play Next and Add to Queue
* Duplicate songs supported as independent queue occurrences
* Manual queue reordering
* Shuffle and repeat modes
* Persistent queue state
* Previous / Next track navigation
* Playback state preservation across app navigation

### Audio transitions

* **Equal-Power Crossfade**

  * Smooth overlap between consecutive tracks
  * Sinusoidal `sin/cos` gain curves
  * Designed to avoid audible volume dips
  * Works with queue, shuffle and repeat behavior

* **Gapless Playback**

  * Uses Media3/ExoPlayer playback transitions
  * Designed for continuous albums and live recordings
  * Avoids unnecessary transition gaps

* **Controlled non-gapless transitions**

  * Clean transition behavior when gapless playback is disabled

### Audio normalization

* Perceptual loudness normalization
* ReplayGain-style loudness processing
* Designed to reduce sudden volume differences between tracks
* Playback-side processing without modifying original audio files

### Audio controls

* 5-band equalizer
* Bass boost
* Virtualizer
* Pitch controls
* Tempo controls
* Crossfade
* Gapless playback
* Audio normalization
* Configurable playback behavior

### Audio routing

* Wired headphones
* Bluetooth audio devices
* Phone speakers
* Android audio output routing
* Automatic handling of headphone disconnection
* Audio-output selection from the player

---

# 🎤 Lyrics

## Synchronized Lyrics

* Timed LRC lyrics
* Current-line highlighting
* Automatic lyric scrolling
* Manual lyric scrolling
* Sync-to-current-line action
* User-adjustable lyrics synchronization offset
* Positive and negative timing offsets
* Lyrics persistence and caching

The lyrics synchronization system uses a single authoritative timing model so that highlighting, scrolling, Sync, and offsets remain consistent.

---

## 🌍 Lyrics Translation & Audio Transcription

OffGrid Music supports two separate AI-powered lyric operations.

### Translate Lyrics

Uses existing lyrics as the source:

```text
Existing Lyrics
      ↓
Translation
      ↓
Translated Lyrics
      ↓
Original timestamps preserved
```

Translation does **not** reprocess the audio.

### Transcribe Audio

When lyrics are unavailable:

```text
Song Audio
    ↓
Audio Transcription
    ↓
Timed Lyrics
    ↓
LRC-style synchronized display
```

Generated lyrics retain timing information so they can synchronize with playback.

### Supported destination languages

* 🇬🇧 English
* 🇮🇳 Tamil
* 🇮🇳 Hindi
* 🇮🇳 Malayalam

The system is designed to handle Tamil/Tanglish and mixed-language audio where supported by the transcription service.

### User-provided Gemini API key

Transcription and translation use the user's own Gemini API key.

Users can:

* Enable/disable the feature
* Add a Gemini API key
* Edit the key
* Delete the key
* Select their preferred destination language

API keys are treated as device-local sensitive configuration and are not part of normal cloud synchronization.

### Lyrics editing safety

Transcription/translation operations support:

* Save
* Undo
* Undo confirmation
* Existing lyrics preservation
* Timestamp preservation
* Existing lyrics offset handling
* Cached/generated lyric results

Failed operations do not replace valid existing lyrics.

---

# 📚 Smart Offline Music Library

OffGrid Music is designed around music stored directly on the device.

### Library categories

* Songs
* Albums
* Artists
* Folders
* Recently Added
* Recently Played
* Favourites
* Playlists

### Sorting

Songs can be organized using options such as:

* Title
* Artist
* Album
* Duration
* Recently Added
* Recently Played

### Automatic music detection

The library can detect newly added audio without requiring the user to repeatedly perform a manual scan.

Supported library behavior includes:

* Included folders
* Excluded/blocklisted folders
* Automatic detection
* Quick Scan
* Full Scan
* Duplicate detection
* Song filtering

Scanning is designed to run without interrupting playback.

---

# 🧹 Duplicate Music Management

The library includes duplicate detection and management for locally stored audio.

Duplicate handling is designed to distinguish between:

* The same physical audio file
* Multiple queue occurrences of the same song

A song appearing multiple times in a playback queue remains a separate queue occurrence and is not incorrectly deduplicated by queue logic.

---

# 🏷️ Tag & Artwork Management

## ID3 / Metadata editing

Edit supported track metadata such as:

* Title
* Artist
* Album
* Other supported tag information

## Artwork

* High-resolution artwork retrieval
* Artwork caching
* Manual artwork support
* Artwork preservation when metadata is edited
* Notification artwork updates
* Consistent artwork across supported player surfaces and widgets

---

# 📋 Playlists

OffGrid Music supports both regular and smart playlists.

### Playlist capabilities

* Create playlists
* Edit playlists
* Delete playlists
* Reorder songs
* Smart playlist rules
* Playlist-specific layouts
* Multiple visual playlist layouts
* Neon Glow Cards layout
* Persistent playlist ordering

Playlist operations preserve song ordering and support duplicate song occurrences where applicable.

---

# 🔀 Queue & Up-Next

The queue system is designed around individual playback occurrences rather than assuming every song is unique.

### Features

* Up-Next queue
* Play Next
* Add to Queue
* Queue reordering
* Drag-and-drop queue management
* Duplicate song occurrences
* Persistent queue
* Current-track positioning
* Shuffle-aware queue behavior
* Repeat support

The current playing occurrence remains distinguishable even when the same song appears multiple times in the queue.

Queue state is designed to survive normal app closing and reopening.

---

# ⏱️ Sleep Timer

Two sleep-timer modes are supported.

### Duration timer

Set playback duration using time.

### Track-based timer

Stop playback after a selected number of future track transitions.

The current track at the moment the timer is enabled is treated as the baseline and is not counted.

Example:

```text
Currently playing: A

B → Track 1
C → Track 2
D → Track 3
E → Track 4
F → Track 5

F finishes
↓
G becomes current at 0:00
↓
Playback stops
```

The track-based timer operates using actual playback/media-item transitions rather than UI timers or progress polling.

---

# 🎨 Themes & Dynamic Visual Design

OffGrid Music provides an extensive visual customization system.

### Theme modes

* Light
* Dark
* AMOLED
* System
* Curated themes

### Accent colors

Users can select their preferred accent color.

### Dynamic Theme

When Dynamic Theme is enabled:

```text
Current Song Artwork
        ↓
Color extraction
        ↓
Dynamic UI palette
        ↓
Application + widgets
```

When Dynamic Theme is disabled:

```text
User-selected Accent Color
        ↓
Application + widgets
```

Dynamic colors are designed to remain consistent across the application and supported widgets.

### Now Playing

Multiple Now Playing layouts are supported, including the **Minimalist Glass** experience.

Waveform visualizations are customizable, including the **Snake Sine** waveform.

---

# 📱 Home Screen Widgets

OffGrid Music provides customizable Android widgets using Jetpack Glance.

Supported widget types include:

* Player Card
* Wide Player
* Compact 3×1 Pill

Widgets provide playback information and controls while adapting to the application's visual/theme state.

The compact pill widget also supports current-song artwork.

---

# 📊 Listening Statistics

Track your listening activity with local statistics including:

* Total listening time
* Play counts
* Recently played
* Most played
* Top tracks
* Top artists
* Top albums
* Listening patterns
* Peak listening periods
* Timeline-based statistics

Supported timeline ranges include:

* Today
* Yesterday
* This Week
* Last 7 Days
* This Month
* Last Month
* This Year
* All Time
* Custom date ranges

---

# 🎞️ Listening Story / Wrapped

Generate visual listening summaries based on listening history.

Story data can include:

* Total listening minutes
* Top tracks
* Top artists
* Listening patterns
* Timeline-specific statistics
* Other supported listening metrics

Story timelines use the same underlying statistics period as the displayed metrics to keep labels and data consistent.

---

# ☁️ Firebase Account & Cloud Sync

OffGrid Music supports optional user accounts through Firebase Authentication.

Supported authentication:

* Google Sign-In
* Email/Password
* Account creation
* Password reset
* Sign out
* Continue without sign-up

Users who do not want an account can continue using the application locally.

### Cloud-synchronized data

For authenticated users, appropriate account-owned data can be synchronized, including:

* User settings
* UI/theme preferences
* Accent color
* Playback preferences
* Favourites
* Playlists
* Listening history
* Listening statistics
* Story data
* Profile information
* Profile picture information

### Offline-first architecture

```text
UI
 ↓
Repository / ViewModel
 ↓
Room Database
 ↓
Cloud Sync
 ↓
Firebase Firestore
```

Room remains the immediate local data source.

Cloud synchronization does not replace local playback or library functionality.

---

# 💾 Automatic Cloud Backup

Users can manage cloud synchronization and backup from:

**Settings → User Account**

Available controls include:

* Automatic Backup ON/OFF
* Daily
* Weekly
* Monthly
* Sync Now
* Backup Now
* Last Synced
* Last Backup

Cloud synchronization runs asynchronously and is designed not to interrupt playback.

### Backup storage

The backup system is designed to retain the latest useful backup state rather than accumulating unlimited historical snapshots.

This helps minimize unnecessary cloud storage consumption.

---

# 👤 User Account

The User Account area provides account and cloud-management functionality including:

* Authentication state
* Profile information
* Profile picture
* Cloud synchronization
* Automatic backup
* Backup frequency
* Last synchronization time
* Last backup time
* Manual Sync Now
* Manual Backup Now

Profile pictures are handled separately from Firestore document data when binary cloud storage is required.

---

# 📦 Local Storage & Offline Operation

The application uses Room for local persistence.

Core functionality remains available without an internet connection, including:

* Local music playback
* Library browsing
* Queue
* Playlists
* Favourites
* Listening statistics
* Lyrics already available locally
* Player settings
* Themes

Cloud functionality becomes available when an authenticated user has network connectivity.

---

# 🛠️ Technology Stack

| Component       | Technology                    |
| --------------- | ----------------------------- |
| Language        | Kotlin                        |
| UI              | Jetpack Compose               |
| Design          | Material 3                    |
| Architecture    | MVVM + Repository             |
| Local Database  | Room                          |
| Async / State   | Kotlin Coroutines + StateFlow |
| Playback        | AndroidX Media3 / ExoPlayer   |
| Media Session   | MediaSessionService           |
| Widgets         | Jetpack Glance                |
| Authentication  | Firebase Authentication       |
| Cloud Database  | Cloud Firestore               |
| Background Work | WorkManager where appropriate |
| AI Lyrics       | Gemini API                    |
| Build           | Gradle                        |
| CI/CD           | GitHub Actions                |

---

# 🏗️ Architecture

OffGrid Music follows an offline-first architecture designed to keep playback independent from network services.

```text
                    ┌──────────────────┐
                    │   Jetpack UI     │
                    └────────┬─────────┘
                             │
                    ┌────────▼─────────┐
                    │ ViewModels /     │
                    │ Repositories     │
                    └───────┬──────────┘
                            │
              ┌─────────────┴─────────────┐
              │                           │
      ┌───────▼────────┐        ┌────────▼────────┐
      │ Room / Local   │        │ Cloud Sync      │
      │ Persistence    │        │ Repository      │
      └───────┬────────┘        └────────┬────────┘
              │                           │
              │                    ┌──────▼───────┐
              │                    │ Firestore    │
              │                    └──────────────┘
              │
      ┌───────▼────────┐
      │ Media3 /       │
      │ ExoPlayer      │
      └────────────────┘
```

Playback remains independent from cloud synchronization so network operations cannot unnecessarily interrupt music playback.

---

# 🔐 Privacy & Data Handling

OffGrid Music is designed around locally stored music.

### Music files

Audio files remain on the user's device and are not uploaded as part of normal cloud synchronization.

### Gemini API key

When Transcribe/Translate is enabled, users provide their own Gemini API key.

The key is treated as sensitive device-local configuration and is not intended to be synchronized through Firestore.

### Cloud account data

Only supported account-owned metadata and application data is synchronized for authenticated users.

---

# 📥 Download the Latest APK

Every push to the repository can trigger the GitHub Actions build workflow.

### GitHub Releases

1. Open **Latest Releases**.
2. Select the newest release.
3. Under **Assets**, download:

```text
app-debug.apk
```

Or use the download button at the top of this README.

> **Android installation:** On the first installation, Android may require permission to install applications from the browser or file manager. Enable **Install unknown apps** for the application being used to install the APK.

---

# 🏗️ Building from Source

### 1. Clone the repository

```bash
git clone https://github.com/<your-username>/<your-repo-name>.git
cd <your-repo-name>
```

### 2. Build the debug APK

```bash
./gradlew assembleDebug
```

### 3. APK location

```text
app/build/outputs/apk/debug/app-debug.apk
```

### 4. Install using ADB

Connect an Android device with USB debugging enabled:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

# 🚀 Project Highlights

OffGrid Music brings together:

* 🎧 Advanced local audio playback
* 🔀 Persistent and duplicate-aware queue management
* 🎚️ Equalizer and audio processing
* 🔊 Crossfade and gapless playback
* 🔉 Audio normalization
* 🎤 Synchronized LRC lyrics
* 🌍 AI-powered lyric translation
* 📝 AI-powered audio transcription
* ⏱️ Duration and track-based sleep timers
* 📂 Automatic local music discovery
* 🏷️ Metadata and artwork editing
* ❤️ Favourites
* 📋 Regular and Smart Playlists
* 📊 Listening statistics
* 🎞️ Listening Stories / Wrapped
* 🎨 Dynamic artwork-based theming
* 📱 Custom Android widgets
* ☁️ Optional Firebase account synchronization
* 💾 Automatic cloud backups
* 👤 User profiles and profile pictures
* 🔒 Offline-first local operation

---

## 🎵 OffGrid Music

**Your music. Your device. Your queue. Your experience.**
