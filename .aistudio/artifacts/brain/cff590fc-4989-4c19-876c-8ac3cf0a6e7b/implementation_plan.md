# In-Place Upgrade, Data Preservation & CI KSP Fix Plan

Ensure the new build seamlessly upgrades existing installations in-place as a single application, preserves all user data (playlists, listening history, favorites, settings), and eliminates the GitHub Actions KSP annotation error while keeping the APK output name as **"OffGrid Music.apk"**.

## User Review & Critical Decisions

> [!IMPORTANT]
> The user confirmed that the app must **not** install as two separate applications on their device (as shown in the launcher screenshot). It must perform an in-place upgrade over existing installations, preserving all local user data and settings, while also resolving the CI KSP error.

- **Confirmed Decision 1 (Single App In-Place Upgrade)**: Revert `applicationId` to the device's primary package name: `com.aistudio.offgridmusic.player`. Android requires the identical package ID to treat an installation as an upgrade rather than a separate app.
- **Confirmed Decision 2 (Prevent Signature Conflict & Preserve Data)**:
  - Increment `versionCode` in `app/build.gradle.kts` (from `3` to `4`, and `versionName` to `"1.0.3"`) so the Android Package Manager recognizes the APK as a newer version of the same app.
  - Sign all builds using the persistent repository `debug.keystore` (SHA-1 `50:22:70:BD:8F:BE:CA:83:06:00:95:5B:4E:DF:FC:67:7E:D8:A0:26`), ensuring the certificate matches the installed package.
  - Because the package ID and signature match, Android automatically preserves all local Room SQLite database files, DataStore preferences, and cache directories without data loss.
- **Confirmed Decision 3 (Fix GitHub Actions KSP Error)**:
  - In `.github/workflows/build-apk.yml`, remove `-Dksp.daemon=false` from `./gradlew assembleDebug`.
  - This allows KSP 2 to launch its background worker with a properly initialized IntelliJ `ApplicationManager`, eliminating the `Application.getService(...) is null` annotation error.
- **Confirmed Decision 4 (Retain APK Renaming)**:
  - Retain `base.archivesName = "OffGrid Music"` and the CI preparation step so the output file and release download is **"OffGrid Music.apk"**.

---

## 1. Overview & Core Concept

### Why Two Apps Appeared on Device
When `applicationId` was changed to `com.aistudio.offgridmusic.app`, Android treated it as an entirely different application. As seen in the app drawer screenshot, this resulted in two side-by-side "OffGrid Music" apps, isolating the new app from the user's existing local database.

### The True Root Cause of the Original Package Conflict
The error `"App not installed as package conflicts with an existing package"` (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`) occurs when Android attempts to update an installed package with an APK that:
1. Has the same package name (`com.aistudio.offgridmusic.player`), but
2. Was signed with a different key than what was previously installed on that device.

### The Complete Resolution
1. **Match Package Name**: Keep `com.aistudio.offgridmusic.player`.
2. **Deterministic Signing**: Both local and CI builds use the exact committed `debug.keystore` (SHA-1: `50:22:70:BD:8F:BE:CA:83:06:00:95:5B:4E:DF:FC:67:7E:D8:A0:26`).
3. **Upgrade Path**: Increment `versionCode` so the OS smoothly executes an in-place upgrade.
4. **Data Preservation**: Android preserves `/data/data/com.aistudio.offgridmusic.player/databases/` (Room database with songs, favorites, playlists, playback stats) and `/shared_prefs/` (theme, EQ, and user settings).

---

## 2. Technical Architecture & File Modifications

```
┌─────────────────────────────────────────────────────────────┐
│                    app/build.gradle.kts                     │
│                                                             │
│  - applicationId = "com.aistudio.offgridmusic.player"       │
│  - versionCode = 4                                          │
│  - versionName = "1.0.3"                                    │
│  - base.archivesName = "OffGrid Music"                      │
│  - Signed with debugConfig (debug.keystore)                 │
└──────────────────────────────┬──────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────┐
│               .github/workflows/build-apk.yml               │
│                                                             │
│  - Remove -Dksp.daemon=false from gradlew invocation        │
│  - Restores persistent debug.keystore before compilation    │
│  - Packages and releases "OffGrid Music.apk"                │
└──────────────────────────────┬──────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────┐
│                    app/google-services.json                 │
│                                                             │
│  - Contains client for com.aistudio.offgridmusic.player     │
│  - Matches debug.keystore SHA-1 certificate hash            │
└─────────────────────────────────────────────────────────────┘
```

### Execution Steps
1. **Update `app/build.gradle.kts`**:
   - Revert `applicationId` to `"com.aistudio.offgridmusic.player"`.
   - Increment `versionCode = 4` and `versionName = "1.0.3"`.
   - Verify `base.archivesName = "OffGrid Music"` is active.
2. **Update `.github/workflows/build-apk.yml`**:
   - Remove `-Dksp.daemon=false` from `./gradlew assembleDebug` command line.
   - Verify keystore restoration and `OffGrid Music.apk` release packaging.
3. **Verify `app/google-services.json`**:
   - Confirm active client config for `com.aistudio.offgridmusic.player`.
4. **Build & Verify**:
   - Run `compile_applet` to verify compilation and packaging without KSP or configuration errors.
