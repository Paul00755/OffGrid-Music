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

Invitation codes work without a web domain and can be entered from the Jam screen. Firebase Hosting provides the `offgrid-music.web.app` and `offgrid-music.firebaseapp.com` hosts, so buying a custom domain is optional.

To use shareable HTTPS links:

1. Deploy Hosting with `firebase deploy --only hosting --project offgrid-music`, or connect a custom domain in Firebase Hosting and wait for its DNS and SSL setup to finish.
2. Set the exact chosen host at Android build time. For the default host, run `./gradlew -PJAM_INVITE_HOST=offgrid-music.web.app assembleRelease` (on Windows, use `./gradlew.bat`). The manifest accepts only `https://<chosen-host>/jam/...` links; rebuild the app after changing the host.
3. Create `public/.well-known/assetlinks.json` with an Android App Links statement for package `com.aistudio.offgridmusic.player` and the SHA-256 certificate fingerprint of the app users will install. For Play releases, use the Play App Signing certificate. For sideloaded releases, use the certificate that signs those APKs. Include multiple fingerprints if you distribute builds signed with different keys.
4. The Hosting ignore list must allow `.well-known` through. Verify `https://<chosen-host>/.well-known/assetlinks.json` returns the JSON file, then deploy Hosting.
5. Install the rebuilt app and test a Jam invitation URL. Without the App Links association, the web fallback and invite code can still be used, but Android may not open the app directly.

The checked-in Hosting target uses the `offgrid-music` Firebase project. This source change does not include a signing-certificate fingerprint or a Firebase deployment.
