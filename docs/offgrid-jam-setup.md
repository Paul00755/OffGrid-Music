# OffGrid Jam setup (Firebase Spark)

OffGrid Jam uses Firebase Authentication and Cloud Firestore for session metadata, votes, queue state, presence, and peer connection signaling. The song audio itself is transferred directly between Android devices over a WebRTC data channel and is saved only on each recipient's device. The app does not upload shared audio to Firebase Storage.

## Firebase setup

1. Open the Firebase project `offgrid-music` and keep it on the **Spark** plan.
2. Enable the authentication provider already used by OffGrid Music.
3. Create the default Cloud Firestore database.
4. From the repository root, deploy only Firestore rules/indexes and Hosting:

   ```powershell
   firebase use offgrid-music
   firebase deploy --only firestore:rules,firestore:indexes,hosting
   ```

Jam no longer requires Cloud Functions or Cloud Storage. Those Firebase products require Blaze and are intentionally absent from this Jam deployment configuration. Firestore's Spark quotas still apply; session signaling and playback state create reads/writes.

Jam operations are coordinated by signed-in clients and constrained by Firestore Security Rules. This keeps the project on Spark, with less tamper resistance than server-authoritative Cloud Functions. FCM remains available for the app's other notification uses, but Jam updates rely on Firestore listeners while participants are online.

## How local song transfer connects

After every Jam participant accepts a local-song proposal, the sender creates a temporary peer handshake in Firestore. The handshake contains connection descriptions and transfer metadata only. WebRTC tries the local network route and then a direct internet route using STUN. The recipient verifies the SHA-256 checksum and audio duration before adding the song to its local library. Transfers are capped at 25 MB.

The same WebRTC transfer supports nearby/local-network peers and direct internet peers. No TURN service is needed when a direct route succeeds. Some carrier, campus, office, or strict-NAT networks block direct peer connections; for those cases, configure a TURN relay. A TURN relay forwards bytes temporarily and does not need to save the audio. TURN is optional and may have its own bandwidth or hosting cost.

For a private test build, set the following Gradle properties or environment variables when building:

```properties
JAM_TURN_URI=turn:turn.example.net:3478
JAM_TURN_USERNAME=temporary-user
JAM_TURN_CREDENTIAL=temporary-credential
```

Without these values, Jam uses a public STUN server and direct peer connections only. Static TURN credentials compiled into an APK can be extracted; use short-lived credentials or leave TURN disabled for distributed builds. Firestore exchanges the offer and answer SDP, then the sender deletes the signaling document after the recipient confirms receipt.

Both devices need to be online in the Jam for a transfer. If the receiving app is stopped or Android restricts its background work, the sender retries and the recipient can retry from the Jam screen. With no TURN relay configured, some internet routes will fail; local-network connections remain available when the devices can reach each other directly.

## Invitation links

Invitation codes can be entered from the Jam screen and do not require a custom domain. For HTTPS share links:

1. Use the Firebase Hosting domain or add a custom domain and configure its DNS.
2. Build with `JAM_INVITE_HOST=your-host.example` as a Gradle property or environment variable. The Android manifest accepts only `https://your-host.example/jam/...` links.
3. Publish `/.well-known/assetlinks.json` with package `com.aistudio.offgridmusic.player` and the SHA-256 fingerprint of the release signing certificate distributed to users.
4. Deploy Hosting. The fallback page shows the invitation code and an Android app link.

The checked-in Hosting target uses project `offgrid-music`. The production domain, release signing fingerprint, and Firebase deployment must be configured by the project owner.
