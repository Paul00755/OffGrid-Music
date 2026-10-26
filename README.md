# 🎵 OffGrid Music

![OffGrid Music Logo](app/src/main/res/drawable/app_logo.png)

**An offline-first, audiophile-grade Android music player built with Kotlin, Jetpack Compose, and AndroidX Media3 — featuring gapless playback, equal-power (`sin`/`cos`) crossfade, custom PCM DSP audio processors, Gemini AI-powered synchronized lyrics & translation, 1:1 finger-tracked queue reordering, 10 curated themes, 12 screen layouts, interactive Wrapped stories, and optional cross-device cloud sync.**

[![Download Latest APK](https://img.shields.io/badge/Download-Latest_APK-00E676?style=for-the-badge&logo=android&logoColor=white)](https://github.com/Paul00755/OffGrid-Music/releases/latest/download/OffGrid-Music.apk)
[![All Versions & Release History](https://img.shields.io/badge/All_Versions-Release_History-9C27B0?style=for-the-badge&logo=github&logoColor=white)](https://github.com/Paul00755/OffGrid-Music/releases)
[![CI/CD Build Pipeline](https://img.shields.io/badge/CI%2FCD-Build_Pipeline-2196F3?style=for-the-badge&logo=githubactions&logoColor=white)](https://github.com/Paul00755/OffGrid-Music/actions/workflows/build-apk.yml)
[![Android 7.0+ (API 24+)](https://img.shields.io/badge/Android-7.0%2B_(API_24%2B)-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://github.com/Paul00755/OffGrid-Music)
[![Kotlin & Jetpack Compose](https://img.shields.io/badge/Kotlin-Jetpack_Compose-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://github.com/Paul00755/OffGrid-Music)

---

## ✨ Highlighted Features at a Glance

- 🎧 **Dual-Player Media3 Audio Engine & Custom PCM DSP**: True gapless playback, **0–12s equal-power (`sin`/`cos`) crossfade**, configurable **Play Fade-In / Pause Fade-Out ramps (`0–5,000 ms`)**, real-time **Perceptual Loudness Normalization (`AudioNormalizerProcessor`)**, **Stereo Channel Balance (`-1.0` L to `+1.0` R)**, and a **5-Band Graphic Equalizer** with **11 presets**, **Bass Boost**, and **3D Virtualizer**.
- 🎤 **Synchronized LRC Lyrics & Gemini AI Lyric Studio**: Real-time scrolling `.lrc` lyrics with tap-to-seek, per-song timing offset (`±ms`), multi-candidate online search, and opt-in **Gemini AI Audio Transcription & Timestamp-Preserving Lyrics Translation** across **English, Tamil (including Tanglish), Hindi, and Malayalam** with one-tap **Undo**.
- 📂 **Precision Deduplicating Scanner & Native System Folder Picker**: Combines `MediaStore` indexing with recursive directory scanning, canonical path deduplication, native Android system file manager (`OpenDocumentTree`) **Include (`+`) / Exclude (`⊘`) folder filters**, **Full-Device Scan confirmation safeguards**, and a **Short Audio Clip Filter (`0–180s`)**.
- 🏷️ **On-Device ID3v2 Tag, Artwork & Lyrics Editor**: Edit Title, Artist, Album, Genre, Year, Track Number, embedded cover art, and `.lrc` companion files directly on device storage, paired with an automatic **Tag Cleaner** and **Duplicate Song Finder**.
- 🔀 **1:1 Finger-Tracked Drag-and-Drop Queue & Playlists**: Occurrence-aware **Up-Next Queue** (allowing the same song multiple times with an active `GraphicEq` **"Now Playing"** status indicator), smooth proportional edge auto-scrolling, **Rule-Based Smart Playlists**, and **M3U Import/Export**.
- 🎨 **Visual & Display Studio (10 Themes × 12 Screen Layouts)**: **4 Display Modes** (*Dark, Light, Pure AMOLED, System*), **10 Curated Themes**, **Dynamic Artwork Color Extraction**, **Custom RGB Color Picker**, **4 Home Layouts**, **4 Now Playing Layouts** (including *Vinyl Turntable* & *Neon Studio*), **4 Playlist Layouts**, **4 Waveform Visualizers**, **9 Reorderable Home Feed Sections**, and **3 Home Screen Widgets**.
- 📊 **Listening Analytics, 6-Slide Wrapped Story & Shareable Cards**: Track listening time, daily bar charts, time-of-day listener archetypes (*Night Owl Explorer, Dawn Melodist, Midday Flow Master, Sonic Harmonizer*), **6-slide interactive Wrapped stories**, and high-resolution shareable stats cards.
- ⏱️ **Dual-Mode Sleep Timer, Room SQLite Tiers & Optional Cloud Sync**: Stop playback by **Duration (minutes)** or **Track Count (songs)**; manage local storage across **Standard (10K) / Expanded (50K) / Maximum (100K)** SQLite tiers; and optionally sync favorites, playlists, stats, and preferences via **Firebase Auth & Cloud Firestore**.

---

## 📥 Download & Installation (Latest & Older Versions)

| Channel | Direct Link | Description |
| :--- | :--- | :--- |
| **🚀 Latest Stable APK (Direct)** | **[⬇️ Download OffGrid-Music.apk](https://github.com/Paul00755/OffGrid-Music/releases/latest/download/OffGrid-Music.apk)** | Immediately downloads the newest automated build directly to your phone. |
| **🔄 Rolling Latest Tag (Mirror)** | **[⬇️ Download from latest Tag](https://github.com/Paul00755/OffGrid-Music/releases/download/latest/OffGrid-Music.apk)** | Direct mirror link to the rolling `latest` channel asset. |
| **🕰️ Older Versions & Release Archive** | **[🗂️ Browse All Releases (v1.0.4-build.X)](https://github.com/Paul00755/OffGrid-Music/releases)** | View the complete version history on the GitHub Releases page and download any previous build (`OffGrid-Music-v1.0.4-build.X.apk`). |
| **⚙️ CI/CD Build Artifacts** | **[📦 GitHub Actions Artifacts](https://github.com/Paul00755/OffGrid-Music/actions/workflows/build-apk.yml)** | Download the `OffGrid-Music-APK` `.zip` artifact from any workflow run. |

### 🔄 Seamless In-Place Upgrades & Downgrades (Zero Data Loss)
- **Updates Existing App Without Conflicts**: Every build is signed with a permanent, deterministic signing certificate (`debug.keystore.base64`) and uses the fixed application ID `com.aistudio.offgridmusic.player`. Installing a new APK updates your existing installation in-place without `"package conflicts with an existing package"` errors or duplicate app icons.
- **Fast, Stall-Free Installation**: Configured with `android:extractNativeLibs="false"` and 16 KB page alignment so Android mounts native libraries directly from the APK without stalling mid-installation.
- **Downgrade to Older Versions Anytime**: All automated builds maintain a unified `versionCode = 5` while incrementing the build number in `versionName` (`1.0.4-build.X`). You can install **any older version** from the **[Releases Page](https://github.com/Paul00755/OffGrid-Music/releases)** directly over a newer version while keeping 100% of your playlists, favorites, cached lyrics, listening statistics, and settings intact.

> **💡 First-Time GitHub Releases Setup (If Clicking Download Shows 404):**
> 1. In your GitHub repository, open **Settings → Actions → General**.
> 2. Scroll to **Workflow permissions**, select **Read and write permissions**, and click **Save**.
> 3. Commit the updated `.github/workflows/build-apk.yml` (or go to **Actions → Build and Release Android APK → Run workflow**).
> 4. Once that first run completes, both the permanent versioned release (`v1.0.4-build.X`) and the rolling `latest` release are published, making the direct download link and release archive work automatically on every push.

---

## 🔍 Comprehensive Codebase Feature Breakdown

### 1. 🎧 Audio Engine, Transitions & Custom DSP Suite
- **Dual-ExoPlayer Architecture (`PlaybackManager` & `MusicPlaybackService`)**:
  - Primary and secondary `ExoPlayer` instances running inside a foreground `MediaSessionService` for uninterrupted background playback, lock-screen controls, notification actions (Previous, Play/Pause, Next, Shuffle, Favorite), and hardware headset button support.
  - Persistent queue, active song, repeat mode, and playback position (`ms`) restoration across app restarts.
- **Equal-Power Crossfade & Smooth Volume Ramps**:
  - **Equal-Power Crossfade (`0–12 seconds`)**: Uses sinusoidal gain curves (`cos(p * π / 2)` fade-out on the outgoing player and `sin(p * π / 2)` fade-in on the incoming player) so perceived loudness never dips mid-transition.
  - **True Gapless Playback**: Pre-buffers upcoming tracks in the `ExoPlayer` media timeline for zero-gap album transitions.
  - **Configurable Play/Pause Fade Ramps**: Independent **Fade-In (`0–5,000 ms`)** and **Fade-Out (`0–5,000 ms`)** cubic curves when resuming or pausing.
- **Custom PCM AudioProcessors & Hardware DSP (`EqualizerManager`)**:
  - **Perceptual Loudness Normalization (`AudioNormalizerProcessor`)**: Real-time ReplayGain-style RMS loudness target (`0.20` RMS, max `+6 dB` boost, soft knee limiter above `0.85` amplitude) with fast attack and smooth release to prevent clipping without modifying source files.
  - **Stereo Channel Balance (`StereoBalanceAudioProcessor`)**: Precision left/right channel gain panning (`-1.0` full Left to `+1.0` full Right) supporting 16-bit PCM, 24-bit PCM, 32-bit PCM, and 32-bit Float streams.
  - **5-Band Graphic Equalizer + 11 Presets**: Custom 5-band sliders (`60 Hz`, `230 Hz`, `910 Hz`, `3.6 kHz`, `14 kHz`) plus **11 built-in presets** (*Normal, Custom, Bass Boost, Treble Boost, Vocal, Rock, Pop, Electronic, Acoustic, Hip-Hop, Classical*), paired with dedicated **Bass Boost (`0–1000`)** and **3D Virtualizer (`0–1000`)** controls.
- **Smart Hardware & Volume Triggers (`AudioRouteManager` & `ShakeDetector`)**:
  - Live **Audio Output Switcher** for seamless routing between **Phone Speaker**, **Wired Headphones / Aux**, **Bluetooth A2DP / BLE Audio**, and **USB DACs**.
  - **Shake to Skip** accelerometer detection, **Pause on Zero Volume**, **Auto-Resume on Volume Increase**, **Auto-Play on Headphone Connection**, **Auto-Play on App Startup**, **Audio Ducking on Notifications**, and **Play During Camera Recording**.

---

### 2. 🎤 Synchronized LRC Lyrics & Gemini AI Lyric Studio
- **Synchronized `.lrc` Lyrics Engine (`LyricsFetcher` & `LrcParser`)**:
  - Multi-stage lyric resolution: checks local Room cache (`LyricsEntity`), companion `.lrc` files on storage, embedded ID3 `USLT`/`SYLT` tags, and online `lrclib.net` search.
  - Interactive synced lyrics view with real-time line highlighting, smooth auto-scroll, tap-line-to-seek, adjustable lyrics font scale (*Small, Medium, Large*), per-song **Timing Offset (`+ms` / `-ms`)**, interactive **Multi-Candidate Online Search**, and custom `.lrc` file import.
- **Gemini AI Lyrics Translation & Audio Transcription (`GeminiLyricsService`)**:
  - Powered by a user-supplied **Gemini API Key** (`gemini-2.5-flash`) stored exclusively on-device using **Android KeyStore AES-256-GCM hardware-backed encryption** (`SecureApiKeyStore` — never included in cloud backups).
  - **Mode 1 — Translate Existing Lyrics**: Translates synced or plain lyrics while strictly preserving every `[mm:ss.xx]` LRC timestamp line-by-line without re-processing audio, backed by a persistent LRU translation cache (`LyricsTranslationCache`).
  - **Mode 2 — Transcribe Audio Directly**: Reads the local audio file (or captures up to 60s via `AudioRecord` fallback) and generates fresh, timestamped `[mm:ss.xx]` `.lrc` lyrics when no lyrics exist.
  - **Supported Target Languages**: 🇬🇧 **English (`en`)**, 🇮🇳 **Tamil (`ta`, including mixed Tanglish audio)**, 🇮🇳 **Hindi (`hi`)**, and 🇮🇳 **Malayalam (`ml`)**.
  - **Preview, Export & One-Tap Undo**: Preview generated lyrics before applying, automatically export companion `.lrc` files to `Music/Lyrics`, and revert to previous lyrics at any time with **one-tap Undo**.

---

### 3. 📂 Precision Local Library Scanner & Folder Filtering
- **Startup Animation → 3-Step Onboarding Flow (`SplashScreen` & `OnboardingScreen`)**:
  - Plays the neon startup animation (`SplashScreen`) first on app launch before transitioning cleanly to the 3-step onboarding wizard (or Home screen for returning users).
  - Uses Android's native system file manager (`ActivityResultContracts.OpenDocumentTree`) to pick **Included (`+`)** and **Excluded (`⊘`)** directories.
  - Replicates the exact Settings **Filter Short Audio Clips** UI (`All Audio`, `15s`, `30s`, `45s`, `1m`, `2m` quick chips + continuous `0–180s` slider) and **Full-Device Scan** confirmation dialog.
  - Scans **only** when the user explicitly taps **Start Library Scan**, and syncs all chosen folders and duration filters directly with **Settings → Library & Storage**.
- **Deduplicating Hybrid Scanner (`LibraryScanner`)**:
  - Combines `MediaStore.Audio.Media` queries with recursive directory traversal across **12 audio formats** (`mp3`, `flac`, `m4a`, `aac`, `ogg`, `opus`, `wav`, `wma`, `amr`, `3gp`, `m4r`, `aiff`).
  - Normalizes canonical file paths (`/storage/emulated/0/...` vs `/sdcard/...`) so every physical audio file is counted and inserted **once** in safe SQLite batches (`250` rows/chunk), preventing inflated track counts or Binder/SQLite transaction overflows.
  - Strictly enforces **Included** and **Excluded** folder rules during both full and quick scans, with a confirmation prompt whenever **Full-Device Scan** is toggled or started while custom folders exist.
  - Background `ContentObserver` and directory `FileObserver` automatically detect newly downloaded songs after onboarding is completed.

---

### 4. 🔀 Queue Management, Playlists & On-Device ID3 Tag Editor
- **Pinned Now Playing Header & 1:1 Drag-and-Drop Queue (`UpNextQueueSheet` & `ReorderableLazyListState`)**:
  - Pins the active **Now Playing** track card above the scrollable **Up Next** list with an animated `GraphicEq` **"Now Playing"** status badge instead of static track numbers.
  - Supports **multiple occurrences of the same song** in the queue via unique composite item keys (`${index}_${song.id}`) while providing a dedicated **Remove Duplicate Songs from Queue** action.
  - **1:1 Finger-Tracked Reordering**: Tracks finger delta directly across list item swaps and performs smooth, proportional edge auto-scrolling when dragging near the top or bottom of the queue or playlist.
- **Regular Playlists, Rule-Based Smart Playlists & M3U Import/Export**:
  - Create custom playlists with **instant custom cover art** (`PlaylistCoverCache` + persistent `filesDir/playlist_covers`), **Remove Duplicates**, **Play Next**, **Add to Queue**, and **M3U / M3U8 playlist import & export** (`M3uParser`).
  - **Smart Playlists (`SmartPlaylistDetailScreen`)**: Dynamic rule-based playlists filtered by **Artist, Genre, Release Year Range, Minimum Play Count, Favorites Only, and Recently Added (Days)**, sorted by *Most Played, Recently Added, Title (A–Z), or Duration* with configurable track limits (`25`, `50`, `100`, `200`, `500`).
- **On-Device ID3v2 Tag, Artwork & Lyrics Editor (`TagEditorDialog` & `AudioTagWriter`)**:
  - Writes **Title, Artist, Album, Genre, Year, Track Number, Embedded Cover Artwork (`APIC`), and Lyrics** directly into physical audio files (constructs standards-compliant ID3v2.3 tags with synchsafe integers for `.mp3` files and updates `MediaStore` + local `.lrc` files).
  - Includes a one-tap **Automatic Tag Cleaner** (strips `[Official Video]`, `(Lyrics)`, website watermarks, and leading track numbers) and a **Duplicate Song Finder** in Settings.

---

### 5. 🎨 Visual Customization Studio & Home Screen Widgets
- **4 Display Modes & 10 Curated Themes (`Theme.kt`)**:
  - **Display Modes**: *Dark Mode, Light Mode, Pure AMOLED Black (`#000000`), and Follow System*.
  - **6 Dark Themes**: *Midnight Obsidian, Pure AMOLED, Cyberpunk Neon, Sunset Ember, Emerald Forest, Ocean Abyss*.
  - **4 Light Themes**: *Daylight Breeze, Soft Pastel, Warm Ivory, Sakura Blossom*.
  - **Accent Colors**: **Dynamic Artwork Color Extraction** (`ArtworkColorExtractor`), **5 Presets** (*Orange, Purple, Pink, Cyan, Green*), plus an interactive **Custom RGB Color Picker** (`0xFF000000..0xFFFFFFFF`).
- **12 Switchable Screen Layouts & 4 Waveform Visualizers**:
  - **4 Home Screen Layouts**: *Bento Dynamic, Hero Spotlight, Minimal Feed, Studio Dashboard* + **9 Reorderable/Toggleable Home Sections** (*Continue Listening, Speed Dial, Listening Stats, Most Played, Most Played Playlists, Recently Played, Recently Added, Top Artists, Top Albums*).
  - **4 Now Playing Layouts**: *Minimal Glass, Modern Bento, Vinyl Turntable (animated spinning record & tonearm), Neon Studio*.
  - **4 Playlists Screen Layouts**: *Glow Cards, Bento Grid, Magazine Hero, Compact List*.
  - **4 Interactive Seek Waveforms**: *Snake Sine, Bars, Dots, Sleek Line* + **3 Mini-Player Styles** (*Floating Pill, Docked Bar, Minimal*) + **Display Scale** (*Compact 0.92×, Default 1.0×, Comfort 1.08×*) + **120Hz High Refresh Rate** support.
- **3 Native Android Home Screen Widgets (`MusicAppWidgetProvider` & `WidgetUpdateManager`)**:
  - **3×3 Card Widget** (`OffGrid 3×3 Card`), **4×2 Wide Player Widget** (`OffGrid 4×2 Wide`), and **3×1 Pill Widget** (`OffGrid 3×1 Pill`) with live album art, progress bar, favorite toggle, shuffle toggle, and playback controls.

---

### 6. 📊 Listening Statistics, 6-Slide Wrapped Story & Cloud Sync
- **Listening Analytics & Shareable Wrapped Stories (`ListeningStatsDetailScreen` & `WrappedStorySheet`)**:
  - Qualified scrobble tracking (`PlaybackHistoryEntity`) configurable by **Seconds (`5–120s`)** or **Percentage (`10–95%`)** of track duration.
  - Filter stats across **Today, Yesterday, Last 7 Days, This Month, and All Time** with 7-day daily bar charts, Top Tracks, Top Artists, Top Albums, and Time-of-Day listener archetypes (*Dawn Melodist, Midday Flow Master, Evening Groove Curator, Night Owl Explorer, Sonic Harmonizer*).
  - **Interactive 6-Slide Wrapped Story** with auto-advancing story progress bars and high-resolution PNG card export (`StatsShareCardGenerator`) for one-tap sharing.
- **Dual-Mode Sleep Timer (`SleepTimerManager`)**:
  - Stop playback after a set **Duration in Minutes** (`15m`, `30m`, `45m`, `60m`, `90m`, or custom `1–720m` with `+5m` quick-extend and *"Finish last song"* option) or after a set **Number of Songs (`1–100 tracks`)**.
- **Room SQLite Storage Tiers & Optional Firebase Cloud Sync (`DatabaseStorageManager` & `CloudSyncRepository`)**:
  - **3 Local SQLite Capacity Tiers**: *Standard (10K history), Expanded (50K history), Maximum (100K history)* with one-tap `WAL` checkpointing, incremental vacuuming, and **Full JSON Database Backup Export / Import**.
  - **Optional Cloud Sync & Scheduled Backups**: Sign in with **Google (`CredentialManager`)** or **Email/Password** (or use the app 100% offline without an account) to synchronize settings, favorites, playlists, smart playlists, folders, and listening history via **Cloud Firestore**, plus scheduled **Daily / Weekly / Monthly** background backups via `WorkManager` (`AutoBackupWorker`).

---

## 🛠️ Technology Stack & Architecture

| Layer | Technology |
| :--- | :--- |
| **Language & UI** | Kotlin 2.0, Jetpack Compose, Material 3, Custom Bento Design System |
| **Architecture** | Offline-First MVVM (`MusicViewModel`) + Repository (`MusicRepository`) + Coroutines & `StateFlow` |
| **Audio & Media** | AndroidX Media3 (`ExoPlayer`, `MediaSessionService`, `ForwardingPlayer`, Custom PCM `AudioProcessor`s) |
| **Local Persistence** | Room SQLite Database (`v10` with migrations & WAL mode), Jetpack DataStore Preferences, SharedPreferences Boot Cache, Android KeyStore (`AES-256-GCM`), Coil Image Cache |
| **Cloud & Background** | Firebase Authentication, Cloud Firestore, AndroidX WorkManager (`AutoBackupWorker`) |
| **AI Integration** | Google Gemini API (`gemini-2.5-flash` for Audio Transcription & Timestamp-Preserving LRC Translation) |
| **CI/CD Pipeline** | GitHub Actions (`ubuntu-24.04`, JDK 17 Temurin, KSP2 Daemon, Deterministic Keystore Signing, Automated Dual Releases) |

```text
                    ┌────────────────────────────────────┐
                    │         Jetpack Compose UI         │
                    │  (10 Themes × 12 Screen Layouts)   │
                    └─────────────────┬──────────────────┘
                                      │
                    ┌─────────────────▼──────────────────┐
                    │   MusicViewModel & MusicRepository │
                    │     (Kotlin Coroutines / Flow)     │
                    └─────────────────┬──────────────────┘
                                      │
         ┌────────────────────────────┼────────────────────────────┐
         │                            │                            │
┌────────▼─────────┐        ┌─────────▼──────────┐       ┌─────────▼─────────┐
│  Room SQLite DB  │        │   Media3 Dual      │       │  Optional Cloud   │
│  & DataStore     │        │   ExoPlayer + DSP  │       │  Sync & Gemini AI │
│ (Songs, Lyrics,  │        │ (Crossfade, EQ,    │       │ (Firestore Sync,  │
│  Playlists,      │        │  Normalizer,       │       │  WorkManager,     │
│  Scrobble Stats) │        │  Stereo Balance)   │       │  LRC Translate)   │
└──────────────────┘        └────────────────────┘       └───────────────────┘
```


---

**🎵 OffGrid Music — Your Music. Your Device. Your Queue. Your Way.**
<!-- Sample push: README update -->
> Sample README update: this line demonstrates a small documentation change pushed from the local workspace.

