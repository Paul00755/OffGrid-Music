# OffGrid Jam setup

OffGrid Jam uses the existing Firebase project plus Cloud Functions, Firestore, Cloud Storage, and Firebase Cloud Messaging. The mobile UI follows the app's selected theme, dynamic artwork palette, gradient, and glass settings.

## Deploy the Firebase backend

Use Node.js 20 and the Firebase CLI. From the repository root:

```powershell
cd functions
npm ci
npm test
cd ..
firebase use offgrid-music
firebase deploy --only functions,firestore:rules,firestore:indexes,storage,hosting
```

Enable the authentication provider already used by the app, Firebase Cloud Messaging, and the project's default Cloud Storage bucket in Firebase Console. The callable functions enforce App Check by default. Enable Play Integrity for the Android app in App Check before using production builds; register debug tokens only for local debug builds.

Local audio sharing is opt-in per Jam. Transfers are capped at 25 MB, kept in a session-scoped Storage path, and expired files are removed by the scheduled function after 24 hours. Firestore stores metadata, votes, progress, and readiness only.

## Configure invitation links

Invitation codes work without a web domain and can be entered from the Jam screen. To use shareable HTTPS links:

1. Add a Firebase Hosting domain and point its DNS records at Firebase Hosting.
2. Build Android with `JAM_INVITE_HOST=your-host.example` as a Gradle property or environment variable. The manifest accepts only `https://your-host.example/jam/...` links.
3. Publish `/.well-known/assetlinks.json` for `com.aistudio.offgridmusic.player`, using the SHA-256 signing-certificate fingerprint for the release key distributed to users. Do not use the debug key fingerprint for a release build.
4. Deploy Hosting. The Jam fallback page displays the code and provides an Android app link when the app is installed.

The checked-in Hosting target uses the `offgrid-music` Firebase project, but no production domain, release signing fingerprint, or Firebase deployment is configured by this source change.
