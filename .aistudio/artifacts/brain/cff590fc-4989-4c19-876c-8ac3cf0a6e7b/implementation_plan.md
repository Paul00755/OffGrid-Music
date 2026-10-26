# OffGrid Music — Video Forensic Bug Fixes & Performance Optimization Plan

This plan resolves the playback audio glitch on track transitions, the 3–4 second UI freeze and frame drops when scrolling the 1,304-track library, the delayed/missing artwork in Now Playing and song lists, and the frozen/overlapping home-screen widgets shown in the screen recording.

## User Review & Critical Decisions

> [!IMPORTANT]
> The following decisions incorporate your confirmed answers and the forensic analysis of the attached video (`00:00 – 01:01`):

- **Confirmed Decision 1 — Widget Progress & Waveform Bar Interaction**: Since Android home-screen launchers intercept horizontal drag gestures for page swiping and do not support continuous `RemoteViews` waveform scrubbing, the Medium and Waveform widgets will render a real-time static progress/waveform indicator (without multi-segment tap-seek or ±10s step buttons). Tapping the widget progress bar or body will immediately open the app directly to the **Now Playing** screen where full interactive waveform scrubbing is available.
- **Confirmed Decision 2 — Natural Gapless vs. Fade-In / Fade-Out Track Transitions**:
  - When **Fade-In / Fade-Out** (and Crossfade) are turned **off** in Settings, Song A to Song B transitions will use pure, unaltered Media3 gapless playback at full volume (`1.0f`).
  - When **Fade-In / Fade-Out** are turned **on**, the end of Song A will smoothly attenuate down to `0.0f` prior to track boundary so Song B already starts at `0.0f` and ramps up cleanly—never dropping an already-playing track down to `0.0f` mid-syllable (`00:24` in the video).
- **Confirmed Decision 3 — Playlist Auto-Covers Preserved**: Existing playlist auto-cover behavior will be kept intact while fixing the underlying scrolling, artwork caching, playback transition, and widget synchronization bugs.

---

## 1. Overview & Core Concept

- **What It Does**: Eliminates UI freezes, scrolling stutter, mid-syllable audio dropouts during track changes, missing Now Playing artwork, and stale/clipped launcher widgets in OffGrid Music.
- **Target Audience / Persona**: Music listeners with large local libraries (1,000+ tracks) who actively multitask between scrolling their library, using the Now Playing screen, and controlling playback from Android home-screen widgets.
- **Key Value**: Buttery-smooth 60/120 Hz scrolling during active playback, glitch-free track transitions, instant artwork display across all views, and real-time widget updates that never freeze or clip on the launcher.

---

## 2. User Experience & Visual Design

- **Key User Flows**:
  1. **Scrolling Library & Home During Playback (`00:00 – 00:33` in video)**: Scrolling through 1,300+ songs remains responsive at full frame rate with thumbnails appearing smoothly and zero multi-second freezes when the playing track changes.
  2. **Song A to Song B Transition (`00:20 – 00:25` in video)**: When a song finishes, the next song begins without a 200ms loud burst followed by a mute drop, and the MiniPlayer and system notification update in lockstep.
  3. **Opening Now Playing (`00:54 – 00:55` in video)**: Tapping the MiniPlayer or a home-screen widget opens the Now Playing screen with the current song's artwork immediately visible (using the in-memory notification bitmap if high-res disk/URI resolution is still in flight).
  4. **Home-Screen Widgets (`00:37 – 01:01` in video)**:
     - All four widgets (**Small**, **Full Artwork**, **Medium**, **Waveform**) immediately reflect the currently playing song, artwork, Play/Pause icon, Favourite heart, and real-time timestamps.
     - Tapping the progress/waveform bar on the **Medium** or **Waveform** widget opens the **Now Playing** screen directly.
- **Visual Identity & Theme**:
  - *Aesthetic Direction*: Deep obsidian glass and neon accent surfaces (`#0E1017` container backgrounds, dynamic artwork-extracted neon lime/orange accents, crisp white typography).
  - *Widget Spatial Layout*:
    - **Small Widget (`2x2`)**: Compact vertical rhythm (`44dp` rounded artwork + top-right Favourite button, single-line title/artist, and `36dp`/`40dp` transport buttons) with zero bottom clipping on compact launcher grids.
    - **Full Artwork Widget (`2x2`)**: Full-bleed rounded album artwork with a bottom dark gradient vignette scrim; metadata is positioned cleanly above the bottom transport row so title/artist text never overlaps the Previous / Play / Next buttons.
    - **Medium & Waveform Widgets (`4x2`)**: Proportional progress and waveform bitmaps rendered without horizontal `fitXY` oval distortion, showing a high-contrast active played wave/bar on the left of the circular thumb and a subtle unplayed track on the right.
- **Interactive Feedback & Motion**:
  - Widget Play/Pause, Previous, Next, and Favourite buttons respond immediately via serialized background broadcasts without interrupting audio playback.

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1: Eliminating Room Table Invalidation Storms During Scrolling**
  - *Chosen Approach*: Remove per-row database writes (`songDao().updateSongArtwork`) from inside the UI artwork component's scroll-time coroutine, keeping resolved URIs in the in-memory and disk cache layer instead.
  - *Why*: Previously, every visible row whose embedded artwork was extracted wrote back to the `songs` table in SQLite. Room's `InvalidationTracker` then re-emitted the entire 1,304-song list on every scrolled item, causing cascading list re-filtering and full-screen recompositions that froze the UI for 3–4 seconds during track transitions.
  - *Alternatives Considered*: Debouncing the Room flow in the ViewModel was considered, but avoiding redundant per-frame SQLite writes during UI rendering eliminates the root I/O and CPU contention entirely.

- **Decision 2: Capping Widget Bitmap Payload & Serializing Updates**
  - *Chosen Approach*: Cap rendered widget background and artwork bitmaps to compact pixel dimensions (well under 150 KB per widget) and route all widget updates through a single conflated coroutine queue.
  - *Why*: Updating four widgets simultaneously every second with full-density `ARGB_8888` bitmaps exceeded Android's 1 MB process-wide Binder IPC buffer (`TransactionTooLargeException`), causing the launcher to silently drop updates and leave all four widgets frozen on the previous track (`4:57 / 4:57`).

- **Decision 3: Pre-Transition Volume Alignment for Gapless vs. Fade Transitions**
  - *Chosen Approach*: Only ramp volume from `0.0f` on natural track transitions if the outgoing track actually faded down (or if `player.volume` was pre-attenuated before the boundary); when Fade-In/Out is off, leave `player.volume = 1.0f` untouched across automatic transitions.
  - *Why*: ExoPlayer's audio thread begins outputting the first ~200ms of Song B before `onMediaItemTransition` arrives on the main thread. Resetting volume to `0.0f` inside `onMediaItemTransition` when Song B was already audible at `1.0f` caused the mid-syllable cutout heard at `00:24` in the video.

---

## 4. Technical Architecture & Data Strategy *(Technical Reference)*

### Architecture & Component Diagram

```
┌──────────────────────────────────────────────────────────────────────────┐
│                        Media3 ExoPlayer Engine                           │
│  • Gapless transition when Fade-In/Out = 0ms (volume stays 1.0f)         │
│  • Pre-boundary fade-out + smooth ramp-up when Fade-In/Out > 0ms         │
│  • AudioSessionId guard prevents redundant Equalizer re-init glitches    │
└───────────────────┬──────────────────────────────────┬───────────────────┘
                    │ Authoritative State & Position   │
                    ▼                                  ▼
┌──────────────────────────────────────┐  ┌────────────────────────────────┐
│       Jetpack Compose UI Layer       │  │   Conflated Widget Pipeline    │
│  • Isolated progress state reads     │  │  • Single serialized worker    │
│    (no full Home/Library recompose)  │  │  • Binder-safe bitmap budget   │
│  • Instant RAM bitmap fallback in    │  │  • Small & Full Artwork fixed  │
│    Now Playing & Song rows           │  │    vertical bounds (no overlap)│
│  • Zero SQLite writes while scrolling│  │  • Tap progress -> Now Playing │
└──────────────────────────────────────┘  └────────────────────────────────┘
```

### Data Model & State
- **Authoritative Playback Snapshot**: Tracks `currentSong`, `isPlaying`, `positionMs`, `durationMs`, and `isFavorite`. Read atomically by both the UI layer and the widget pipeline so a Favourite toggle or progress tick never overwrites a track change or resets elapsed time.
- **Two-Tier Artwork Cache**:
  1. *In-Memory Notification & Thumbnail Cache*: Immediately supplies decoded `Bitmap` instances to `NowPlayingScreen`, `MiniPlayer`, and widgets on the first frame.
  2. *Disk & Memory URI Cache*: Stores extracted embedded artwork files without mutating SQLite rows during list scrolling.

### Interactive Component & State Mapping
- **Widget Progress Bar Tap -> Open Now Playing**: Tapping the progress/waveform bar (or widget body) dispatches an `Intent` to `MainActivity` with `EXTRA_OPEN_NOW_PLAYING = true`, which sets `viewModel.showNowPlaying.value = true` so the user lands directly on the interactive Now Playing screen.
- **Widget Transport & Favourite Controls**: `ACTION_PLAY_PAUSE`, `ACTION_NEXT`, `ACTION_PREV`, and `ACTION_FAVORITE` execute against the singleton `PlaybackManager`, update the persisted snapshot, and enqueue a conflated full widget refresh.
- **Home "Continue Listening" Card**: Observes playback progress inside a scoped child composable so 1-second timer updates only redraw the progress label/bar without recomposing the parent `HomeScreen` list while scrolling.
