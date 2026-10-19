# Package Conflict Resolution & APK Renaming Plan

Resolve the Android "App not installed as package conflicts with an existing package" error by updating the Application ID to allow clean installation alongside older versions, and configure the build system and GitHub Actions workflow to output the APK as **"OffGrid Music.apk"**.

## User Review & Critical Decisions

> [!IMPORTANT]
> The user confirmed updating the Application ID (`applicationId = "com.aistudio.offgridmusic.app"`) so that the new build installs smoothly on Android without signature mismatch conflicts against older packages, and requested the APK output name to be **"OffGrid Music.apk"**.

- **Confirmed Decision 1**: Update `applicationId` to `com.aistudio.offgridmusic.app`.
  - Android will treat this as a clean package that never collides with older versions on the user's device.
  - Automatically isolates FileProvider authority (`${applicationId}.fileprovider`).
- **Confirmed Decision 2**: Rename APK output in Gradle from `app-debug.apk` to `OffGrid Music.apk`.
- **Confirmed Decision 3**: Update `.github/workflows/build-apk.yml` and `README.md` to reference `OffGrid Music.apk` across CI/CD release publishing and direct download links.

---

## 1. Overview & Core Concept

- **Problem**: When attempting to install a new build over an older version signed with a previous temporary certificate, Android Package Manager aborts with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` ("App not installed as package conflicts with an existing package").
- **Solution**:
  1. Assign a fresh, stable `applicationId` (`com.aistudio.offgridmusic.app`) so installation succeeds both on clean devices and on devices with older builds installed.
  2. Configure Gradle `applicationVariants` output naming to generate `OffGrid Music.apk`.
  3. Ensure `.github/workflows/build-apk.yml` handles spaces in the APK filename correctly when uploading artifacts and publishing releases.

---

## 2. Technical Architecture & File Modifications

```
┌────────────────────────────────────────────────────────┐
│                   app/build.gradle.kts                 │
│                                                        │
│  - applicationId = "com.aistudio.offgridmusic.app"     │
│  - outputFileName = "OffGrid Music.apk"                │
└──────────────────────────┬─────────────────────────────┘
                           │
                           ▼
┌────────────────────────────────────────────────────────┐
│              .github/workflows/build-apk.yml           │
│                                                        │
│  - Artifact: "OffGrid Music.apk"                       │
│  - Release Asset: "OffGrid Music.apk"                  │
│  - Quotes & Escaping for Spaces in Path                │
└──────────────────────────┬─────────────────────────────┘
                           │
                           ▼
┌────────────────────────────────────────────────────────┐
│                       README.md                        │
│                                                        │
│  - Direct Download: releases/latest/download/          │
│    OffGrid%20Music.apk                                 │
└────────────────────────────────────────────────────────┘
```

### Execution Steps
1. **Update `app/build.gradle.kts`**:
   - Change `applicationId` to `"com.aistudio.offgridmusic.app"`.
   - Add variant output renaming to produce `"OffGrid Music.apk"`.
2. **Update `.github/workflows/build-apk.yml`**:
   - Ensure APK path wildcards and release upload commands handle `"OffGrid Music.apk"` with proper shell quoting.
3. **Update `README.md`**:
   - Update download links and documentation to refer to `"OffGrid Music.apk"`.
4. **Compile & Package**:
   - Run Gradle to build `OffGrid Music.apk` and verify the output artifact.
