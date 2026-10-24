# Implementation Plan: High-Performance Scrolling, Artwork Prefetching & Modular Settings

Deliver butter-smooth 60–120 FPS performance across the entire application—specifically targeting massive track libraries (5,000 to 50,000+ tracks), eliminating artwork loading lag through proactive prefetching, transforming the Settings screen into instant modular category sub-pages, and optimizing glassmorphism to eliminate GPU overdraw, all while strictly adhering to the Zero-Regression Mandate.

---

## 1. Confirmed User Decisions & Clarifications

Based on user clarifications:
- **Massive Track List Scrolling & Artwork Pre-Rendering**: Artwork must **not** load on-the-fly when the user scrolls an item into view. Instead, artwork will be pre-fetched, decoded, and cached ahead of the viewport into Coil's memory cache so album art is instantly visible with zero frame drops or pop-in during fast flings.
- **Settings Screen Modularization**: Replace the monolithic 4,700+ line `SettingsScreen` list with a clean category hub and instant sub-screen page transitions (e.g., General, Audio & Playback, Appearance & Themes, Library & Scanner, Gestures & Mini-Player, Advanced & Backups). Each sub-page isolates state and recompositions.
- **Framerate vs. Visual Effects Balance**: Maximize framerate across 60Hz, 90Hz, and 120Hz displays by replacing heavy dynamic `Modifier.blur()` / RenderEffect glassmorphism with optimized semi-transparent surface tokens (`surfaceContainer` / dark tinted alpha with subtle borders).
- **Zero-Regression Mandate**: All playback controls, background audio services, lock screen/notification widgets, and DataStore/Room persistence will remain 100% stable and untouched.

---

## 2. Technical Architecture & Performance Pipeline

```
┌────────────────────────────────────────────────────────────────────────┐
│               Massive Library Performance Architecture                 │
│                                                                        │
│  ┌───────────────────────┐             ┌────────────────────────────┐  │
│  │   Track LazyColumn    │             │   Coil Artwork Preloader   │  │
│  │  - key = { song.id }  │             │  - Window: visibleIndex    │  │
│  │  - contentType        │  Dispatches │    + 25 items ahead        │  │
│  │  - derivedStateOf     │ ──────────> │  - Decodes off-thread      │  │
│  │  - FastScroller popup │   Window    │  - Stores in MemoryCache   │  │
│  └───────────┬───────────┘             └─────────────┬──────────────┘  │
│              │                                       │                 │
│              ▼                                       ▼                 │
│  ┌───────────────────────┐             ┌────────────────────────────┐  │
│  │  SongItem Composable  │ ◄────────── │ Instant Cache Hit in UI    │  │
│  │  - Zero recomposition │  Pre-cached │ (No shimmer, no pop-in,    │  │
│  │  - Lightweight canvas │   Bitmap    │  Zero dropped frames)      │  │
│  └───────────────────────┘             └────────────────────────────┘  │
└────────────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────────────┐
│              Modular Settings Navigation Architecture                  │
│                                                                        │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │              Settings Hub (Category Grid / Bento Cards)           │  │
│  │   [Audio Engine]   [Appearance & Theme]   [Library & Scanning]   │  │
│  │   [Mini-Player]    [Now Playing Themes]   [Backup & Storage]     │  │
│  └──────────────────────────────────┬───────────────────────────────┘  │
│                                     │ Instant Transition               │
│                                     ▼                                  │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │              Isolated Sub-Screen Category Page                   │  │
│  │   - BackHandler for instant return to Settings Hub               │  │
│  │   - Localized state flow; zero recomposition outside active page │  │
│  │   - Smooth, fluid navigation with zero jank                      │  │
│  └──────────────────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Step-by-Step Implementation

### Step 1: Ahead-of-Viewport Artwork Prefetch Pipeline (`ArtworkPreloader.kt` & `AlbumArtView.kt`)
1. **Windowed Memory Preloader**:
   - Create an `ArtworkPreloader` that observes `lazyListState.firstVisibleItemIndex`.
   - Computes a lookahead window (e.g., current visible indices + 25 items ahead in the scroll direction).
   - Enqueues lightweight Coil `ImageRequest`s with `memoryCacheKey` directly into the Coil `ImageLoader` on `Dispatchers.IO`:
     - Targets the song's `albumArtUri` or `content://media/external/audio/albumart`.
     - Sizes down directly to the exact list thumbnail dimension (e.g., 48dp / 96px) to avoid decoding large unscaled bitmaps into memory.
2. **Instant Bitmap Retrieval in `AlbumArtView`**:
   - Ensure `AlbumArtView` queries the preloaded memory cache synchronously before falling back to disk/placeholder.
   - Eliminate unnecessary image crossfade and complex multi-layered shimmer loaders when bitmaps are already cached in RAM.

### Step 2: Virtualization & Optimization of Track Lists (`LibraryScreen.kt`, `SongItem.kt`)
1. **LazyColumn Best Practices**:
   - Supply explicit `contentType = { "song_item" }` so Compose pools and reuses item nodes efficiently across 50,000+ tracks.
   - Use stable integer/long keys (`key = { song.id }`).
   - Wrap fast-scroller bubble calculations and header elevation in `derivedStateOf` to prevent recomposing the list container during scroll events.
2. **SongItem Recomposition Shielding**:
   - Mark `SongItem` parameters as `@Stable`/`@Immutable` or pass primitive IDs.
   - Hoist click lambdas (`remember(song.id) { { onSongClick(song) } }`).
   - Simplify item layout to single-pass measure hierarchy (avoid nested weights or unnecessary layout passes).

### Step 3: Modularize Settings Screen into Sub-Pages (`SettingsScreen.kt`)
1. **Settings Hub**:
   - When `currentSection == SettingsSection.MAIN`, render an intuitive, modern Bento-grid / list of category cards (Audio, Appearance, Library, Interface, Advanced) with summary subtitles and clean icons.
2. **Dedicated Modular Sub-Pages**:
   - Separate category contents into distinct modular composable functions:
     - `AudioSettingsPage` (Equalizer, crossfade, replay gain, audio focus).
     - `AppearanceSettingsPage` (Themes, dynamic color, typography, accent colors).
     - `LibrarySettingsPage` (Scan settings, folder management, duration filters).
     - `InterfaceSettingsPage` (Now playing style, mini-player gestures, lyrics layout).
     - `AdvancedSettingsPage` (Cache management, backup/restore, metadata).
   - Wire standard `BackHandler` inside sub-screens to return instantly to the Settings Hub.
   - Isolate recomposition so tweaking an equalizer slider or color theme never re-evaluates other unrelated settings.

### Step 4: Streamline Glassmorphic Blurs to High-FPS Modern Surfaces
1. **Eliminate Real-Time `RenderEffect` GPU Overhead**:
   - In Now Playing backgrounds, bottom navigation bars, and dialog backdrops, replace expensive continuous `Modifier.blur()` filters with optimized semi-transparent surfaces:
     - `MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)`
     - Subtle 1dp border `MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)`
   - Retain identical modern dark/light aesthetic while eliminating GPU fillrate bottlenecks, guaranteeing locked 60/120fps even during active animations.

### Step 5: Verification & Zero-Regression Testing
1. **Zero-Regression Checks**:
   - Audio playback: Verify background playback, seekbar real-time updates, queue manipulation, and notification controls work seamlessly.
   - DataStore persistence: Verify settings changes in modular sub-pages persist immediately.
   - Onboarding scan & folder selection: Confirm the recently integrated folder modal and scan filters remain completely functional.
2. **Build & Compilation Verification**:
   - Run `compile_applet` to confirm clean compilation with zero Kotlin/Gradle warnings or errors.

---

## 4. Acceptance Criteria
- [ ] Track list scrolling is fluid and stutter-free across small and massive libraries.
- [ ] Artwork is preloaded ahead of the viewport with immediate appearance and zero flicker.
- [ ] Settings screen opens instantly and navigates smoothly between modular category pages.
- [ ] Blurs replaced with clean, lightweight surface tokens providing high framerates.
- [ ] Core audio playback, notification widgets, and database operations remain 100% regression-free.
