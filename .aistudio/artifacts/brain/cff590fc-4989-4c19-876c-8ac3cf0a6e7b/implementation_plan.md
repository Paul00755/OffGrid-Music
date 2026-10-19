# Seamless In-Place Upgrade, Local Data Retention & CI KSP Fix Plan

Ensure every app update acts strictly as an in-place upgrade over existing installations (never creating a duplicate app), guarantees that local user details, playlists, favorites, and settings are preserved without erasure, and eliminates the GitHub Actions KSP build error by enabling the KSP daemon and updating CI workflow dependencies.

## User Review & Critical Decisions

> [!IMPORTANT]
> The user confirmed that updates must **never erase local user data** (playlists, favorites, listening history, custom settings) and must **never install as two separate applications**. Upgrading must be seamless, in-place, and retain all local preferences. Additionally, GitHub Actions KSP daemon configuration and CI runner dependencies must be fixed.

- **Confirmed Decision 1 (Single App In-Place Upgrade)**: Lock `applicationId = "com.aistudio.offgridmusic.player"` in `app/build.gradle.kts`. This ensures Android recognizes the new APK as the exact same application as the existing installation, avoiding duplicate app icons on the device launcher.
- **Confirmed Decision 2 (Prevent Data Erasure Across Updates)**:
  - Increment `versionCode = 4` and `versionName = "1.0.3"` so Android's PackageManager performs a smooth version upgrade over the existing app.
  - Sign all builds with the deterministic repository `debug.keystore` (SHA-1 `50:22:70:BD:8F:BE:CA:83:06:00:95:5B:4E:DF:FC:67:7E:D8:A0:26`), preventing `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.
  - Update `data_extraction_rules.xml` and `backup_rules.xml` to include `offgrid_music_db` and `user_settings.preferences_pb`, allowing Android system backup and app updates to preserve local database tables and settings files.
  - Add an automatic safety check on startup in `DatabaseStorageManager` to verify database continuity and auto-restore library entities if an update is detected.
- **Confirmed Decision 3 (GitHub Actions CI KSP & Runner Fix)**:
  - In `.github/workflows/build-apk.yml`, remove `-Dksp.daemon=false` so KSP 2 can launch its daemon worker with a valid IntelliJ `ApplicationManager`, eliminating the annotation error shown in the screenshot.
  - Update `actions/setup-java@v5` to `actions/setup-java@v4` to resolve the Node.js 20 deprecation warning.
- **Confirmed Decision 4 (APK Output Naming)**:
  - Maintain Gradle `base.archivesName = "OffGrid Music"` and workflow packaging so the artifact and release download is consistently named **"OffGrid Music.apk"**.

---

## 1. Overview & Core Concept

### Root Causes
1. **Data Loss & Duplicate App**:
   - Changing `applicationId` created a sandboxed empty app (`com.aistudio.offgridmusic.app`), resulting in two icons and appearing to have "erased" user data.
   - Restricting `applicationId` to `com.aistudio.offgridmusic.player` guarantees access to the existing `/data/data/com.aistudio.offgridmusic.player/` sandbox.
2. **KSP Annotation Error in CI**:
   - Running `./gradlew assembleDebug` with `-Dksp.daemon=false` forces KSP into an in-process mode that leaves IntelliJ's `ApplicationManager.getApplication()` null. Removing this flag restores normal daemon execution.

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
│    data_extraction_rules.xml & backup_rules.xml             │
│                                                             │
│  - Include offgrid_music_db database                        │
│  - Include user_settings.preferences_pb preferences         │
│  - Safeguard local data across OS update routines           │
└──────────────────────────────┬──────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────┐
│               .github/workflows/build-apk.yml               │
│                                                             │
│  - Remove -Dksp.daemon=false (Enables KSP daemon)           │
│  - Use actions/setup-java@v4 (Resolves Node.js deprecation) │
│  - Packages release asset as "OffGrid Music.apk"            │
└─────────────────────────────────────────────────────────────┘
```

### Execution Steps
1. **Update `app/build.gradle.kts`**:
   - Ensure `applicationId = "com.aistudio.offgridmusic.player"`.
   - Set `versionCode = 4` and `versionName = "1.0.3"`.
2. **Update `data_extraction_rules.xml` & `backup_rules.xml`**:
   - Configure rules to preserve `offgrid_music_db` and user settings across system upgrades and backups.
3. **Update `.github/workflows/build-apk.yml`**:
   - Remove `-Dksp.daemon=false` so the KSP daemon runs cleanly.
   - Update `actions/setup-java@v5` to `actions/setup-java@v4`.
4. **Compile & Verify**:
   - Run `compile_applet` to verify clean build without KSP errors.
