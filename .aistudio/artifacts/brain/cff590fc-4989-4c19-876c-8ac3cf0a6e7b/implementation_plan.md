# Playlist Pre-Buffering & System-Wide Smoothness Plan

Implement an optimized pre-buffering strategy for upcoming tracks in the playlist while the current track is playing, and enhance full-app UI/render smoothness to eliminate lag, stutter, and frame drops across all devices.

## User Review & Critical Decisions

> [!IMPORTANT]
> The user confirmed balanced pre-buffering to prime upcoming tracks in memory without triggering high RAM usage on lower-end devices, alongside complete app smoothness optimizations (60fps scrolling, zero-jank UI, and zero-latency audio transitions).

- **Confirmed Decision 1**: Implement a tuned `DefaultLoadControl` and media source pipeline in ExoPlayer to pre-buffer the upcoming track while the current one is still playing, ensuring the decoder and audio sink are primed before the transition.
- **Confirmed Decision 2**: Balanced memory strategy: Allocate up to 30–60 seconds of forward buffer for the active track and prime the next playlist track's headers and audio metadata in advance.
- **Confirmed Decision 3**: Optimize Compose UI lists, image caching, and high-frequency state emissions to ensure solid 60/120fps scrolling with zero hitching across all devices.

---

## 1. Overview & Core Concept

- **What It Does**:
  1. **Playback Pre-Buffering**: Pre-buffers the next track in the playlist while the current song is actively playing, so the player buffer is already filled and ready the instant track $A \to B$ transition occurs.
  2. **App-Wide Smoothness**: Streamlines Compose recompositions, list keys, image caching, and widget/notification updates to ensure buttery-smooth navigation, scrolling, and audio playback.
- **Key Value**: Instantaneous track transitions with zero latency, paired with a lag-free, responsive UI across all screen types and device tiers.

---

## 2. User Experience & Visual Design

- **Continuous Playback**:
  - The upcoming song's audio buffer is primed in advance.
  - When the current song reaches its end, the transition to the next track is immediate and seamless (0ms silence or stutter).
- **UI Responsiveness & Scrolling**:
  - Song list, playlist, and album views scroll fluidly without hitching or frame drops.
  - Artwork transitions and Now Playing screen animations use cached bitmaps, preventing on-scroll disk reads.

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1: Custom `DefaultLoadControl` with Playlist Pre-Loading**:
  - *Chosen Approach*: Configure `DefaultLoadControl` in `ExoPlayer.Builder` with:
    - Minimum buffer: 30s, Maximum buffer: 60s
    - Playback buffer threshold: 1s, Rebuffer threshold: 2s
    - Back-buffer duration: 15s
    - Prioritize time over size thresholds
  - *Why*: Ensures the upcoming track in the playlist timeline begins pre-buffering before track A finishes, guaranteeing the decoder buffer is full on transition.
- **Decision 2: Asynchronous Pre-Warming of Next Track**:
  - *Chosen Approach*: When active track reaches 85% completion, pre-warm the next track's artwork, duration, and color extraction into in-memory LRU cache in the background.
  - *Why*: Eliminates all UI hitching and main-thread work when the transition event fires.
- **Decision 3: High-Frequency UI State Decoupling**:
  - *Chosen Approach*: Keep playback tracking and progress updates decoupled from database operations and heavy widget re-renders, adhering strictly to the user directive.

---

## 4. Technical Architecture & Data Strategy

```
┌─────────────────────────────────────────────────────────────────┐
│                    Tuned ExoPlayer Pipeline                     │
│                                                                 │
│   Active Track A (Playing) ────► Buffer: 30s-60s primed        │
│   Next Track B (Queued)    ────► Timeline prepared & preloaded │
│                                                                 │
│   [DefaultLoadControl]                                          │
│   - minBuffer: 30s, maxBuffer: 60s                              │
│   - playbackStart: 1s                                           │
│   - backBuffer: 15s                                             │
└────────────────────────────────┬────────────────────────────────┘
                                 │
                                 ▼ (85% Track Progress)
┌─────────────────────────────────────────────────────────────────┐
│                 Background Pre-Warming Engine                   │
│                                                                 │
│   - Pre-decode upcoming track artwork into LRU Memory Cache     │
│   - Pre-extract palette colors for dynamic theme                │
│   - Pre-warm metadata and lyrics cache                          │
└─────────────────────────────────────────────────────────────────┘
```

### Execution Steps
1. **Configure ExoPlayer LoadControl (`PlaybackManager.kt`)**:
   - Build and attach an optimized `DefaultLoadControl` on `player` and `crossfadePlayer` in `PlaybackManager.kt`.
2. **Implement Upcoming Track Pre-Warming (`PlaybackManager.kt` & `MusicViewModel.kt`)**:
   - In `startProgressTracker()`, when remaining playback time is under 15 seconds, pre-warm the upcoming track's artwork and metadata into memory cache.
3. **Optimize Compose Scrolling & UI Smoothness**:
   - Verify that all LazyColumns in `HomeScreen`, `PlaylistsScreen`, and `SearchScreen` use stable item keys and memory-cached artwork to guarantee smooth 60fps scrolling.
4. **Build & Verify**:
   - Run compilation and packaging tasks to verify zero regressions.
