import { initializeApp } from "firebase-admin/app";
import { FieldPath, FieldValue, getFirestore, Timestamp, type DocumentData, type QueryDocumentSnapshot, type Transaction } from "firebase-admin/firestore";
import { createHash, randomUUID } from "node:crypto";
import { getMessaging } from "firebase-admin/messaging";
import { getStorage } from "firebase-admin/storage";
import { HttpsError, onCall, type CallableRequest } from "firebase-functions/v2/https";
import { onSchedule } from "firebase-functions/v2/scheduler";
import {
  applyPlaybackCommand,
  canPerform,
  generateInviteCode,
  invitationDigest,
  MAX_PARTICIPANTS,
  MAX_QUEUE_SIZE,
  normalizeInviteCode,
  recommendationDecision,
  requiredApprovals,
  type PermissionKey,
  type Permissions,
  type PlaybackItem,
  type PlaybackState,
  validatePermissions,
  validateTrack
} from "./domain";

initializeApp();
const db = getFirestore();
const REGION = "us-central1";
const PRESENCE_STALE_MS = 3 * 60_000;
const INVITE_LIFETIME_MS = 7 * 24 * 60 * 60_000;
const COMMAND_HISTORY_TTL_MS = 24 * 60 * 60_000;
const RECOMMENDATION_WINDOW_MS = 30_000;
const MAX_LOCAL_SONG_BYTES = 25 * 1024 * 1024;
const ALLOWED_AUDIO_CONTENT_TYPES = new Set([
  "audio/mpeg", "audio/mp4", "audio/aac", "audio/flac", "audio/x-flac",
  "audio/ogg", "audio/wav", "audio/x-wav"
]);
const TRANSFER_LIFETIME_MS = 24 * 60 * 60_000;
const CALLABLE_OPTIONS = { region: REGION, enforceAppCheck: process.env.JAM_REQUIRE_APP_CHECK !== "false" };

type Data = Record<string, unknown>;

function requireUid(request: CallableRequest<unknown>): string {
  const uid = request.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in to use OffGrid Jam.");
  return uid;
}

function record(value: unknown): Data {
  return value !== null && typeof value === "object" && !Array.isArray(value) ? value as Data : {};
}

function requiredString(value: unknown, message: string, maxLength = 180): string {
  if (typeof value !== "string" || !value.trim() || value.length > maxLength) {
    throw new HttpsError("invalid-argument", message);
  }
  return value.trim();
}

function safePhotoUrl(value: unknown): string {
  if (typeof value !== "string" || !value) return "";
  if (value.length > 500 || !value.startsWith("https://")) throw new HttpsError("invalid-argument", "Profile image URL is invalid.");
  return value;
}

function sessionRef(id: string) { return db.collection("jamSessions").doc(id); }
function participantRef(id: string, uid: string) { return sessionRef(id).collection("participants").doc(uid); }
function queueRef(id: string) { return sessionRef(id).collection("queue"); }
function inviteRef(hash: string) { return db.collection("jamInvitations").doc(hash); }
function recommendationRef(sessionId: string, recommendationId: string) {
  return sessionRef(sessionId).collection("recommendations").doc(recommendationId);
}

function sessionIsActive(data: DocumentData | undefined): data is DocumentData {
  return !!data && data.status === "ACTIVE";
}

function assertActiveSession(data: DocumentData | undefined): asserts data is DocumentData {
  if (!data || data.status !== "ACTIVE") throw new HttpsError("failed-precondition", "This Jam is no longer active.");
}

function participantRecord(uid: string, displayName: string, photoUrl: string, isHost: boolean) {
  const timestamp = FieldValue.serverTimestamp();
  return {
    uid,
    displayName: displayName || "Listener",
    photoUrl,
    role: isHost ? "HOST" : "PARTICIPANT",
    joinedAt: timestamp,
    lastSeenAt: timestamp
  };
}

function requireMember(member: DocumentData | undefined): asserts member is DocumentData {
  if (!member) throw new HttpsError("permission-denied", "You are not a participant in this Jam.");
}

function requireHost(session: DocumentData, uid: string) {
  if (session.hostUid !== uid) throw new HttpsError("permission-denied", "Only the host can do that.");
}

function permissionForCommand(commandType: string): PermissionKey {
  if (commandType === "PLAY_PAUSE") return "playPause";
  if (commandType === "SEEK") return "seek";
  if (commandType === "SKIP_TRACK") return "skipTrack";
  throw new HttpsError("invalid-argument", "Unsupported playback command.");
}

function requirePermission(session: DocumentData, command: PermissionKey, uid: string) {
  if (!canPerform(session.permissions as Permissions, command, uid, String(session.hostUid))) {
    throw new HttpsError("permission-denied", "You don't have permission to do that.");
  }
}

async function rateLimit(uid: string, action: string, maxCount: number, windowMs: number) {
  const ref = db.collection("jamRateLimits").doc(`${uid}_${action}`);
  const now = Date.now();
  await db.runTransaction(async transaction => {
    const snapshot = await transaction.get(ref);
    const startedAt = snapshot.get("startedAtMs") as number | undefined;
    const count = snapshot.get("count") as number | undefined;
    const currentCount = startedAt && now - startedAt < windowMs ? (count ?? 0) : 0;
    if (currentCount >= maxCount) throw new HttpsError("resource-exhausted", "Too many Jam requests. Please wait and try again.");
    transaction.set(ref, {
      startedAtMs: currentCount === 0 ? now : startedAt,
      count: currentCount + 1,
      updatedAt: FieldValue.serverTimestamp()
    });
  });
}

async function pushRecommendation(sessionId: string, uids: string[], data: Record<string, string>) {
  const unique = [...new Set(uids)].filter(uid => uid !== data.proposerUid);
  const groups = (await Promise.all(unique.map(async uid => {
    const docs = await db.collection("jamDeviceTokens").doc(uid).collection("tokens").get();
    return { uid, tokens: docs.docs.map(doc => ({ token: doc.get("token") as string, ref: doc.ref })) };
  }))).filter(group => group.tokens.length > 0);
  if (groups.length === 0) return;
  const invalidCodes = new Set(["messaging/invalid-registration-token", "messaging/registration-token-not-registered"]);
  const removals = (await Promise.all(groups.map(async group => {
    const response = await getMessaging().sendEachForMulticast({
      tokens: group.tokens.map(item => item.token),
      android: { priority: "high" },
      data: { ...data, sessionId, recipientUid: group.uid }
    });
    return response.responses.flatMap((item, index) =>
      !item.success && item.error && invalidCodes.has(item.error.code) ? [group.tokens[index].ref.delete()] : []
    );
  }))).flat();
  if (removals.length) await Promise.all(removals);
}

function addTrackAfterCurrent(
  transaction: Transaction,
  sessionId: string,
  session: DocumentData,
  queueDocs: QueryDocumentSnapshot[],
  track: PlaybackItem["track"],
  addedByUid: string
): boolean {
  if (queueDocs.length >= MAX_QUEUE_SIZE) return false;
  const currentItemId = String(record(session.playback).queueItemId ?? "");
  let insertionIndex = queueDocs.findIndex(doc => doc.id === currentItemId) + 1;
  if (insertionIndex <= 0) insertionIndex = 0;
  for (let index = queueDocs.length - 1; index >= insertionIndex; index--) {
    const doc = queueDocs[index];
    transaction.update(doc.ref, { position: index + 1 });
  }
  const item = queueRef(sessionId).doc(randomUUID());
  transaction.create(item, {
    track,
    position: insertionIndex,
    addedByUid,
    addedAt: FieldValue.serverTimestamp(),
    source: "RECOMMENDATION"
  });
  return true;
}

function allowedAudioSignature(bytes: Buffer): boolean {
  if (bytes.length < 4) return false;
  const ascii = bytes.subarray(0, 12).toString("ascii");
  return ascii.startsWith("ID3") ||
    (bytes[0] === 0xff && (bytes[1] & 0xe0) === 0xe0) ||
    ascii.startsWith("fLaC") || ascii.startsWith("OggS") ||
    (ascii.startsWith("RIFF") && bytes.subarray(8, 12).toString("ascii") === "WAVE") ||
    (bytes.subarray(4, 8).toString("ascii") === "ftyp");
}

function failTransfersForDepartingParticipants(
  transaction: Transaction,
  proposals: QueryDocumentSnapshot[],
  departingUids: Set<string>
) {
  for (const proposal of proposals) {
    const data = proposal.data();
    if (!(["APPROVED_PENDING_UPLOAD", "UPLOADING", "DOWNLOADING"].includes(String(data.status)))) continue;
    const required = (data.eligibleUids as string[] | undefined) ?? [];
    if (!required.some(uid => departingUids.has(uid))) continue;
    transaction.update(proposal.ref, {
      status: "FAILED",
      ...(data.proposerUid && departingUids.has(String(data.proposerUid)) ? { uploadStatus: "UPLOAD_FAILED" } : {}),
      failureReason: "A required participant left before the song was ready on every device.",
      failedAt: FieldValue.serverTimestamp(),
      transferExpiresAt: Timestamp.fromMillis(Date.now() + 60 * 60_000)
    });
  }
}

function toPlaybackItems(snapshots: QueryDocumentSnapshot[]): PlaybackItem[] {
  return snapshots.map(snapshot => ({
    id: snapshot.id,
    position: Number(snapshot.get("position") ?? 0),
    track: snapshot.get("track") as PlaybackItem["track"]
  }));
}

function safeSessionId(value: unknown): string {
  const id = requiredString(value, "Session ID is missing.", 64);
  if (!/^[A-Za-z0-9-]{16,64}$/.test(id)) throw new HttpsError("invalid-argument", "Session ID is invalid.");
  return id;
}

export const createJam = onCall(CALLABLE_OPTIONS, async request => {
  const uid = requireUid(request);
  const input = record(request.data);
  const displayName = requiredString(input.displayName, "Add a display name to your account.", 80);
  const photoUrl = safePhotoUrl(input.photoUrl);
  let permissions: Permissions;
  try {
    permissions = validatePermissions(input.permissions);
  } catch (error) {
    throw new HttpsError("invalid-argument", error instanceof Error ? error.message : "Jam permissions are invalid.");
  }

  const sessionId = randomUUID();
  const invitationCode = generateInviteCode();
  const inviteHash = invitationDigest(invitationCode);
  const session = sessionRef(sessionId);
  const invite = inviteRef(inviteHash);
  const initialPlayback = {
    sharedTrackId: "",
    queueItemId: "",
    isPlaying: false,
    positionMs: 0,
    playbackSpeed: 1,
    updatedAt: FieldValue.serverTimestamp(),
    revision: 0,
    commandId: "",
    commandType: "CREATE",
    actorUid: uid,
    step: 0
  };
  await db.runTransaction(async transaction => {
    const existingInvite = await transaction.get(invite);
    if (existingInvite.exists) throw new HttpsError("aborted", "Please try creating the invitation again.");
    transaction.create(session, {
      hostUid: uid,
      status: "ACTIVE",
      participantCount: 1,
      maxParticipants: MAX_PARTICIPANTS,
      inviteCodeHash: inviteHash,
      createdAt: FieldValue.serverTimestamp(),
      permissions,
      playback: initialPlayback,
      revision: 1,
      queueRevision: 0
    });
    transaction.create(participantRef(sessionId, uid), participantRecord(uid, displayName, photoUrl, true));
    transaction.create(invite, {
      sessionId,
      active: true,
      createdAt: FieldValue.serverTimestamp(),
      expiresAt: Timestamp.fromMillis(Date.now() + INVITE_LIFETIME_MS)
    });
  });
  return { sessionId, invitationCode };
});

export const joinJam = onCall(CALLABLE_OPTIONS, async request => {
  const uid = requireUid(request);
  await rateLimit(uid, "join", 15, 60_000);
  const input = record(request.data);
  const code = normalizeInviteCode(input.invitationCode);
  if (code.length !== 16) throw new HttpsError("invalid-argument", "Invitation code is invalid.");
  const displayName = requiredString(input.displayName, "Add a display name to your account.", 80);
  const photoUrl = safePhotoUrl(input.photoUrl);
  const invitation = inviteRef(invitationDigest(code));

  return db.runTransaction(async transaction => {
    const inviteSnapshot = await transaction.get(invitation);
    if (!inviteSnapshot.exists || inviteSnapshot.get("active") !== true) throw new HttpsError("not-found", "This invitation is invalid or has been revoked.");
    const expiresAt = inviteSnapshot.get("expiresAt") as Timestamp | undefined;
    if (!expiresAt || expiresAt.toMillis() <= Date.now()) throw new HttpsError("deadline-exceeded", "This invitation has expired.");
    const sessionId = safeSessionId(inviteSnapshot.get("sessionId"));
    const session = sessionRef(sessionId);
    const member = participantRef(sessionId, uid);
    const [sessionSnapshot, memberSnapshot] = await Promise.all([transaction.get(session), transaction.get(member)]);
    const sessionData = sessionSnapshot.data();
    assertActiveSession(sessionData);
    if (memberSnapshot.exists) return { sessionId };

    const count = Number(sessionData.participantCount ?? 0);
    if (count >= MAX_PARTICIPANTS) throw new HttpsError("resource-exhausted", "This Jam is full (maximum 10 people).");
    transaction.create(member, participantRecord(uid, displayName, photoUrl, false));
    transaction.update(session, {
      participantCount: count + 1,
      revision: Number(sessionData.revision ?? 0) + 1
    });
    return { sessionId };
  });
});

export const heartbeatJam = onCall(CALLABLE_OPTIONS, async request => {
  const uid = requireUid(request);
  const input = record(request.data);
  const id = safeSessionId(input.sessionId);
  const displayName = requiredString(input.displayName, "Display name is invalid.", 80);
  const photoUrl = safePhotoUrl(input.photoUrl);
  const session = sessionRef(id);
  const member = participantRef(id, uid);
  return db.runTransaction(async transaction => {
    const [sessionSnapshot, selfSnapshot] = await Promise.all([transaction.get(session), transaction.get(member)]);
    const sessionData = sessionSnapshot.data();
    assertActiveSession(sessionData);
    if (!selfSnapshot.exists) throw new HttpsError("permission-denied", "You are not in this Jam.");
    const now = Date.now();
    const hostUid = String(sessionData.hostUid);
    const hostRef = participantRef(id, hostUid);
    const hostSnapshot = hostUid === uid ? selfSnapshot : await transaction.get(hostRef);
    let participants: QueryDocumentSnapshot[] | null = null;
    if (hostUid !== uid && now - ((hostSnapshot.get("lastSeenAt") as Timestamp | undefined)?.toMillis() ?? 0) > PRESENCE_STALE_MS) {
      participants = (await transaction.get(session.collection("participants").orderBy("joinedAt", "asc"))).docs;
    }
    transaction.update(member, {
      displayName,
      photoUrl,
      lastSeenAt: FieldValue.serverTimestamp()
    });
    if (participants) {
      const eligible = participants.filter(item => item.id !== hostUid);
      const replacement = eligible[0];
      if (replacement) {
        transaction.update(session, {
          hostUid: replacement.id,
          revision: Number(sessionData.revision ?? 0) + 1
        });
        transaction.update(replacement.ref, { role: "HOST" });
        transaction.update(hostRef, { role: "PARTICIPANT" });
      }
    }
    return { ok: true };
  });
});

export const updateJamPermissions = onCall(CALLABLE_OPTIONS, async request => {
  const uid = requireUid(request);
  const input = record(request.data);
  const id = safeSessionId(input.sessionId);
  const session = sessionRef(id);
  return db.runTransaction(async transaction => {
    const snapshot = await transaction.get(session);
    const data = snapshot.data();
    assertActiveSession(data);
    requireHost(data, uid);
    const members = await transaction.get(session.collection("participants"));
    const participantUids = new Set(members.docs.map(doc => doc.id));
    let permissions: Permissions;
    try {
      permissions = validatePermissions(input.permissions, participantUids);
    } catch (error) {
      throw new HttpsError("invalid-argument", error instanceof Error ? error.message : "Jam permissions are invalid.");
    }
    transaction.update(session, { permissions, revision: Number(data.revision ?? 0) + 1 });
    return { ok: true };
  });
});

export const mutateJamQueue = onCall(CALLABLE_OPTIONS, async request => {
  const uid = requireUid(request);
  await rateLimit(uid, "queue", 60, 60_000);
  const input = record(request.data);
  const id = safeSessionId(input.sessionId);
  const operation = requiredString(input.operation, "Queue operation is missing.", 16).toUpperCase();
  if (!["ADD", "REMOVE", "REORDER"].includes(operation)) throw new HttpsError("invalid-argument", "Queue operation is invalid.");
  const session = sessionRef(id);
  const member = participantRef(id, uid);
  const collection = queueRef(id);

  return db.runTransaction(async transaction => {
    const [sessionSnapshot, memberSnapshot, queueSnapshot] = await Promise.all([
      transaction.get(session), transaction.get(member), transaction.get(collection.orderBy("position", "asc").limit(MAX_QUEUE_SIZE))
    ]);
    const data = sessionSnapshot.data();
    assertActiveSession(data);
    requireMember(memberSnapshot.data());
    const permissions = data.permissions as Permissions;
    const hostUid = String(data.hostUid);
    const items = queueSnapshot.docs;
    const queueRevision = Number(data.queueRevision ?? 0) + 1;
    const sessionRevision = Number(data.revision ?? 0) + 1;

    if (operation === "ADD") {
      if (!canPerform(permissions, "addToQueue", uid, hostUid)) throw new HttpsError("permission-denied", "You don't have permission to add tracks.");
      if (items.length >= MAX_QUEUE_SIZE) throw new HttpsError("resource-exhausted", "The shared queue is full.");
      let track;
      try { track = validateTrack(input.track); }
      catch (error) { throw new HttpsError("invalid-argument", error instanceof Error ? error.message : "Track details are invalid."); }
      const queueItemId = randomUUID();
      const position = items.reduce((max, item) => Math.max(max, Number(item.get("position") ?? -1)), -1) + 1;
      transaction.create(collection.doc(queueItemId), {
        id: queueItemId,
        track,
        addedByUid: uid,
        position,
        createdAt: FieldValue.serverTimestamp()
      });
      transaction.update(session, { queueRevision, revision: sessionRevision });
      return { queueItemId };
    }

    if (!canPerform(permissions, "manageQueue", uid, hostUid)) throw new HttpsError("permission-denied", "You don't have permission to manage the queue.");
    if (operation === "REMOVE") {
      const queueItemId = requiredString(input.queueItemId, "Queue item is missing.", 64);
      const index = items.findIndex(item => item.id === queueItemId);
      if (index < 0) throw new HttpsError("aborted", "The shared queue changed. Refresh and try again.");
      transaction.delete(items[index].ref);
      const remaining = items.filter((_, itemIndex) => itemIndex !== index);
      remaining.forEach((item, position) => transaction.update(item.ref, { position }));
      const updates: Data = { queueRevision, revision: sessionRevision };
      const playback = data.playback as PlaybackState;
      if (playback.queueItemId === queueItemId) {
        const replacement = remaining[index] ?? remaining[index - 1];
        const nextPlayback = {
          ...playback,
          sharedTrackId: replacement?.get("track")?.sharedTrackId ?? "",
          queueItemId: replacement?.id ?? "",
          isPlaying: !!replacement && playback.isPlaying,
          positionMs: 0,
          updatedAt: FieldValue.serverTimestamp(),
          revision: Number(playback.revision ?? 0) + 1,
          commandId: randomUUID(),
          commandType: "QUEUE_CHANGE",
          actorUid: uid
        };
        updates.playback = nextPlayback;
      }
      transaction.update(session, updates);
      return { ok: true };
    }

    const requestedIds = input.queueItemIds;
    if (!Array.isArray(requestedIds) || requestedIds.some(item => typeof item !== "string")
      || requestedIds.length !== items.length || new Set(requestedIds).size !== items.length
      || [...requestedIds].sort().join("\u001f") !== items.map(item => item.id).sort().join("\u001f")) {
      throw new HttpsError("aborted", "The shared queue changed. Refresh and try again.");
    }
    const byId = new Map(items.map(item => [item.id, item]));
    requestedIds.forEach((itemId, position) => transaction.update(byId.get(itemId as string)!.ref, { position }));
    transaction.update(session, { queueRevision, revision: sessionRevision });
    return { ok: true };
  });
});

export const registerJamDeviceToken = onCall(CALLABLE_OPTIONS, async request => {
  const uid = requireUid(request);
  const input = record(request.data);
  const token = requiredString(input.token, "Notification token is invalid.", 4096);
  const tokenId = createHash("sha256").update(token).digest("hex");
  await db.collection("jamDeviceTokens").doc(uid).collection("tokens").doc(tokenId).set({
    token,
    updatedAt: FieldValue.serverTimestamp()
  });
  return { ok: true };
});

export const createJamRecommendation = onCall(CALLABLE_OPTIONS, async request => {
  const uid = requireUid(request);
  await rateLimit(uid, "recommendation", 8, 60_000);
  const input = record(request.data);
  const id = safeSessionId(input.sessionId);
  const type = requiredString(input.recommendationType, "Recommendation type is invalid.", 24) as "SHARED_LIBRARY" | "LOCAL_SONG";
  if (type !== "SHARED_LIBRARY" && type !== "LOCAL_SONG") throw new HttpsError("invalid-argument", "Recommendation type is invalid.");
  let track: PlaybackItem["track"];
  try { track = validateTrack(input.track); }
  catch (error) { throw new HttpsError("invalid-argument", error instanceof Error ? error.message : "Track details are invalid."); }
  const session = sessionRef(id);
  const member = participantRef(id, uid);
  const queueQuery = queueRef(id).orderBy("position", "asc").limit(MAX_QUEUE_SIZE);
  const created = await db.runTransaction(async transaction => {
    const [sessionSnapshot, memberSnapshot, members, queue] = await Promise.all([
      transaction.get(session), transaction.get(member),
      transaction.get(session.collection("participants").orderBy("joinedAt", "asc")),
      transaction.get(queueQuery)
    ]);
    const data = sessionSnapshot.data();
    assertActiveSession(data);
    requireMember(memberSnapshot.data());
    const permissions = data.permissions as Permissions;
    if (type === "SHARED_LIBRARY") {
      if (permissions.manageQueue.mode !== "HOST_ONLY") {
        throw new HttpsError("failed-precondition", "Shared-library recommendations are only used when queue management is host-only.");
      }
      if (permissions.allowSharedLibraryRecommendations !== true) throw new HttpsError("permission-denied", "Shared-library recommendations are disabled by the host.");
      const shared = queue.docs.find(item => item.get("track")?.sharedTrackId === track.sharedTrackId);
      if (!shared) throw new HttpsError("failed-precondition", "Choose a song already in the Jam shared library.");
      track = shared.get("track") as PlaybackItem["track"];
    } else if (permissions.allowLocalSongSharing !== true) {
      throw new HttpsError("permission-denied", "Local-song sharing is disabled by the host.");
    } else if (queue.docs.some(item => item.get("track")?.sharedTrackId === track.sharedTrackId)) {
      throw new HttpsError("already-exists", "This song is already in the Jam shared library.");
    }
    const eligibleUids = members.docs.map(doc => doc.id);
    if (!eligibleUids.includes(uid) || eligibleUids.length === 0 || eligibleUids.length > MAX_PARTICIPANTS) {
      throw new HttpsError("failed-precondition", "The Jam participant list changed. Try again.");
    }
    const name = String(memberSnapshot.get("displayName") ?? "Listener");
    const proposalId = randomUUID();
    const requiredApprovalCount = requiredApprovals(eligibleUids.length, type);
    const votes = Object.fromEntries(eligibleUids.map(participantUid => [participantUid, participantUid === uid ? "ACCEPT" : "PENDING"]));
    const status = requiredApprovalCount === 1
      ? (type === "LOCAL_SONG" ? "APPROVED_PENDING_UPLOAD" : "READY")
      : "VOTING";
    const recommendation = recommendationRef(id, proposalId);
    const now = Date.now();
    transaction.create(recommendation, {
      sessionId: id,
      recommendationId: proposalId,
      recommendationType: type,
      proposerUid: uid,
      proposerName: name,
      track,
      eligibleUids,
      votes,
      createdAt: FieldValue.serverTimestamp(),
      deadlineAt: Timestamp.fromMillis(now + RECOMMENDATION_WINDOW_MS),
      status,
      approvalCount: 1,
      rejectionCount: 0,
      requiredApprovalCount,
      downloads: {},
      ...(type === "LOCAL_SONG" ? {
        storagePath: `jam-temp/${id}/${proposalId}/audio`,
        uploadStatus: "WAITING_FOR_UPLOAD",
        transferExpiresAt: Timestamp.fromMillis(now + TRANSFER_LIFETIME_MS)
      } : {})
    });
    if (type === "SHARED_LIBRARY" && status === "READY") {
      const inserted = addTrackAfterCurrent(transaction, id, data, queue!.docs, track, uid);
      if (!inserted) throw new HttpsError("resource-exhausted", "The shared queue is full.");
      transaction.update(session, { queueRevision: Number(data.queueRevision ?? 0) + 1, revision: Number(data.revision ?? 0) + 1 });
    }
    return { proposalId, eligibleUids, proposerName: name, track, status };
  });
  await pushRecommendation(id, created.eligibleUids, {
    kind: "jam_recommendation",
    recommendationId: created.proposalId,
    recommendationType: type,
    proposerUid: uid,
    title: `${created.proposerName} recommended a song`,
    body: `${created.track.title} · vote in the next 30 seconds`
  }).catch(() => undefined);
  return { recommendationId: created.proposalId, status: created.status };
});

export const voteJamRecommendation = onCall(CALLABLE_OPTIONS, async request => {
  const uid = requireUid(request);
  const input = record(request.data);
  const id = safeSessionId(input.sessionId);
  const proposalId = requiredString(input.recommendationId, "Recommendation ID is missing.", 64);
  const vote = requiredString(input.vote, "Choose accept or reject.", 12).toUpperCase();
  if (vote !== "ACCEPT" && vote !== "REJECT") throw new HttpsError("invalid-argument", "Vote must be accept or reject.");
  const session = sessionRef(id);
  const proposal = recommendationRef(id, proposalId);
  const member = participantRef(id, uid);
  const result = await db.runTransaction(async transaction => {
    const [sessionSnapshot, memberSnapshot, proposalSnapshot] = await Promise.all([
      transaction.get(session), transaction.get(member), transaction.get(proposal)
    ]);
    const sessionData = sessionSnapshot.data();
    assertActiveSession(sessionData);
    requireMember(memberSnapshot.data());
    if (!proposalSnapshot.exists) throw new HttpsError("not-found", "This recommendation no longer exists.");
    const data = proposalSnapshot.data()!;
    if (data.status !== "VOTING") throw new HttpsError("failed-precondition", "Voting for this recommendation is already closed.");
    const eligible = data.eligibleUids as string[];
    if (!eligible.includes(uid)) throw new HttpsError("permission-denied", "You were not part of this recommendation's voting group.");
    const deadline = data.deadlineAt as Timestamp | undefined;
    if (!deadline || deadline.toMillis() <= Date.now()) {
      transaction.update(proposal, { status: "EXPIRED", finalResult: "EXPIRED", completedAt: FieldValue.serverTimestamp() });
      return { status: "EXPIRED", notify: false, track: data.track as PlaybackItem["track"], uids: eligible };
    }
    const votes = { ...record(data.votes) } as Record<string, string>;
    if (votes[uid] !== "PENDING") throw new HttpsError("already-exists", "You have already voted.");
    votes[uid] = vote;
    const approvalCount = Object.values(votes).filter(value => value === "ACCEPT").length;
    const rejectionCount = Object.values(votes).filter(value => value === "REJECT").length;
    const required = Number(data.requiredApprovalCount ?? 1);
    const type = String(data.recommendationType);
    let status: string = recommendationDecision({
      type: type as "SHARED_LIBRARY" | "LOCAL_SONG",
      approvals: approvalCount,
      rejections: rejectionCount,
      pending: eligible.length - approvalCount - rejectionCount,
      required,
      deadlineExpired: false
    });
    let queueDocs: QueryDocumentSnapshot[] = [];
    if (status === "READY") {
      queueDocs = (await transaction.get(queueRef(id).orderBy("position", "asc").limit(MAX_QUEUE_SIZE))).docs;
      const track = data.track as PlaybackItem["track"];
      if (!addTrackAfterCurrent(transaction, id, sessionData, queueDocs, track, String(data.proposerUid))) status = "FAILED";
    }
    const updates: Data = { votes, approvalCount, rejectionCount, status };
    if (status !== "VOTING") {
      updates.completedAt = FieldValue.serverTimestamp();
      updates.finalResult = status === "READY" ? "APPROVED" : status;
    }
    transaction.update(proposal, updates);
    if (status === "READY") transaction.update(session, {
      queueRevision: Number(sessionData.queueRevision ?? 0) + 1,
      revision: Number(sessionData.revision ?? 0) + 1
    });
    return { status, notify: status !== "VOTING", track: data.track as PlaybackItem["track"], uids: eligible };
  });
  if (result.notify) await pushRecommendation(id, result.uids, {
    kind: "jam_recommendation_result",
    recommendationId: proposalId,
    title: result.status === "READY" || result.status === "APPROVED_PENDING_UPLOAD" ? "Jam recommendation approved" : "Jam recommendation closed",
    body: `${result.track.title} · ${result.status.toLowerCase().replaceAll("_", " ")}`
  }).catch(() => undefined);
  return { status: result.status };
});

export const beginJamUpload = onCall(CALLABLE_OPTIONS, async request => {
  const uid = requireUid(request);
  const input = record(request.data);
  const id = safeSessionId(input.sessionId);
  const proposalId = requiredString(input.recommendationId, "Recommendation ID is missing.", 64);
  const session = sessionRef(id);
  const member = participantRef(id, uid);
  const proposal = recommendationRef(id, proposalId);
  const result = await db.runTransaction(async transaction => {
    const [sessionSnapshot, memberSnapshot, proposalSnapshot] = await Promise.all([
      transaction.get(session), transaction.get(member), transaction.get(proposal)
    ]);
    assertActiveSession(sessionSnapshot.data());
    requireMember(memberSnapshot.data());
    if (!proposalSnapshot.exists) throw new HttpsError("not-found", "This recommendation no longer exists.");
    const data = proposalSnapshot.data()!;
    if (data.recommendationType !== "LOCAL_SONG" || data.proposerUid !== uid || !["APPROVED_PENDING_UPLOAD", "UPLOADING"].includes(String(data.status))) {
      throw new HttpsError("permission-denied", "This approved upload is not available to your account.");
    }
    transaction.update(proposal, { status: "UPLOADING", uploadStatus: "UPLOADING", uploadStartedAt: FieldValue.serverTimestamp() });
    return { storagePath: String(data.storagePath), maxBytes: MAX_LOCAL_SONG_BYTES, eligible: data.eligibleUids as string[], track: data.track as PlaybackItem["track"], proposerUid: uid };
  });
  await pushRecommendation(id, result.eligible, {
    kind: "jam_upload_started",
    recommendationId: proposalId,
    proposerUid: result.proposerUid,
    title: "Song upload started",
    body: `${result.track.title} is being uploaded for the Jam.`
  }).catch(() => undefined);
  return { storagePath: result.storagePath, maxBytes: result.maxBytes };
});

export const updateJamUploadProgress = onCall(CALLABLE_OPTIONS, async request => {
  const uid = requireUid(request);
  const input = record(request.data);
  const id = safeSessionId(input.sessionId);
  const proposalId = requiredString(input.recommendationId, "Recommendation ID is missing.", 64);
  const progress = Math.max(0, Math.min(100, Math.floor(Number(input.progress ?? 0))));
  const proposal = recommendationRef(id, proposalId);
  await db.runTransaction(async transaction => {
    const [sessionSnapshot, memberSnapshot, proposalSnapshot] = await Promise.all([
      transaction.get(sessionRef(id)), transaction.get(participantRef(id, uid)), transaction.get(proposal)
    ]);
    assertActiveSession(sessionSnapshot.data());
    requireMember(memberSnapshot.data());
    if (!proposalSnapshot.exists || proposalSnapshot.get("proposerUid") !== uid || proposalSnapshot.get("status") !== "UPLOADING") {
      throw new HttpsError("permission-denied", "This upload is not active for your account.");
    }
    const last = Number(proposalSnapshot.get("uploadProgressUpdatedAtMs") ?? 0);
    const oldProgress = Number(proposalSnapshot.get("uploadProgress") ?? 0);
    const now = Date.now();
    if (now - last < 4_000 && Math.abs(progress - oldProgress) < 5) return;
    transaction.update(proposal, { uploadProgress: progress, uploadProgressUpdatedAtMs: now, uploadUpdatedAt: FieldValue.serverTimestamp() });
  });
  return { ok: true };
});

export const failJamUpload = onCall(CALLABLE_OPTIONS, async request => {
  const uid = requireUid(request);
  const input = record(request.data);
  const id = safeSessionId(input.sessionId);
  const proposalId = requiredString(input.recommendationId, "Recommendation ID is missing.", 64);
  const error = typeof input.error === "string" ? input.error.slice(0, 160) : "Upload failed.";
  const proposal = recommendationRef(id, proposalId);
  const result = await db.runTransaction(async transaction => {
    const [sessionSnapshot, memberSnapshot, proposalSnapshot] = await Promise.all([
      transaction.get(sessionRef(id)), transaction.get(participantRef(id, uid)), transaction.get(proposal)
    ]);
    assertActiveSession(sessionSnapshot.data());
    requireMember(memberSnapshot.data());
    if (!proposalSnapshot.exists || proposalSnapshot.get("proposerUid") !== uid || proposalSnapshot.get("status") !== "UPLOADING") {
      throw new HttpsError("permission-denied", "This upload is not active for your account.");
    }
    transaction.update(proposal, { status: "FAILED", uploadStatus: "UPLOAD_FAILED", failureReason: error, failedAt: FieldValue.serverTimestamp() });
    return { eligible: proposalSnapshot.get("eligibleUids") as string[], track: proposalSnapshot.get("track") as PlaybackItem["track"] };
  });
  await pushRecommendation(id, result.eligible, {
    kind: "jam_upload_failed",
    recommendationId: proposalId,
    proposerUid: uid,
    title: "Song upload failed",
    body: `${result.track.title} could not be uploaded. The Jam queue continues as usual.`
  }).catch(() => undefined);
  return { ok: true };
});

export const retryJamUpload = onCall(CALLABLE_OPTIONS, async request => {
  const uid = requireUid(request);
  const input = record(request.data);
  const id = safeSessionId(input.sessionId);
  const proposalId = requiredString(input.recommendationId, "Recommendation ID is missing.", 64);
  const proposal = recommendationRef(id, proposalId);
  await db.runTransaction(async transaction => {
    const [sessionSnapshot, memberSnapshot, proposalSnapshot] = await Promise.all([
      transaction.get(sessionRef(id)), transaction.get(participantRef(id, uid)), transaction.get(proposal)
    ]);
    assertActiveSession(sessionSnapshot.data());
    requireMember(memberSnapshot.data());
    if (!proposalSnapshot.exists || proposalSnapshot.get("proposerUid") !== uid || proposalSnapshot.get("status") !== "FAILED") {
      throw new HttpsError("permission-denied", "This upload cannot be retried.");
    }
    const path = proposalSnapshot.get("storagePath") as string | undefined;
    const expiresAt = proposalSnapshot.get("transferExpiresAt") as Timestamp | undefined;
    if (!path || !expiresAt || expiresAt.toMillis() <= Date.now()) throw new HttpsError("deadline-exceeded", "This temporary upload has expired.");
    transaction.update(proposal, {
      status: "APPROVED_PENDING_UPLOAD",
      uploadStatus: "WAITING_FOR_UPLOAD",
      failureReason: "",
      transferExpiresAt: Timestamp.fromMillis(Date.now() + TRANSFER_LIFETIME_MS)
    });
  });
  return { ok: true };
});

export const completeJamUpload = onCall(CALLABLE_OPTIONS, async request => {
  const uid = requireUid(request);
  const input = record(request.data);
  const id = safeSessionId(input.sessionId);
  const proposalId = requiredString(input.recommendationId, "Recommendation ID is missing.", 64);
  const proposal = recommendationRef(id, proposalId);
  const proposalSnapshot = await proposal.get();
  if (!proposalSnapshot.exists || proposalSnapshot.get("proposerUid") !== uid || proposalSnapshot.get("status") !== "UPLOADING") {
    throw new HttpsError("permission-denied", "This upload is not authorized.");
  }
  const storagePath = requiredString(proposalSnapshot.get("storagePath"), "Storage location is missing.", 300);
  const file = getStorage().bucket().file(storagePath);
  const [metadata] = await file.getMetadata().catch(() => { throw new HttpsError("not-found", "The uploaded audio file could not be found."); });
  const fileSize = Number(metadata.size ?? 0);
  const contentType = String(metadata.contentType ?? "").toLowerCase();
  if (fileSize < 4 || fileSize > MAX_LOCAL_SONG_BYTES || !ALLOWED_AUDIO_CONTENT_TYPES.has(contentType)) {
    await file.delete({ ignoreNotFound: true }).catch(() => undefined);
    throw new HttpsError("invalid-argument", "The uploaded audio file is invalid or too large.");
  }
  const [bytes] = await file.download();
  if (!allowedAudioSignature(bytes)) {
    await file.delete({ ignoreNotFound: true }).catch(() => undefined);
    throw new HttpsError("invalid-argument", "The uploaded file is not a supported audio format.");
  }
  const checksum = createHash("sha256").update(bytes).digest("hex");
  const session = sessionRef(id);
  const transactionResult = await db.runTransaction(async transaction => {
    const [sessionSnapshot, memberSnapshot, currentProposal] = await Promise.all([
      transaction.get(session), transaction.get(participantRef(id, uid)), transaction.get(proposal)
    ]);
    const sessionData = sessionSnapshot.data();
    assertActiveSession(sessionData);
    requireMember(memberSnapshot.data());
    if (!currentProposal.exists || currentProposal.get("status") !== "UPLOADING" || currentProposal.get("proposerUid") !== uid) {
      throw new HttpsError("aborted", "The recommendation changed while the file was being checked.");
    }
    const data = currentProposal.data()!;
    const eligible = data.eligibleUids as string[];
    const downloads: Data = Object.fromEntries(eligible.map(participantUid => [participantUid, {
      // Every participant, including the proposer, installs the verified shared copy
      // locally before this track can become eligible for the common queue.
      status: "WAITING_TO_DOWNLOAD",
      progress: 0,
      checksumVerified: false,
      locallyAvailable: false,
      readyForPlayback: false,
      updatedAtMs: Date.now()
    }]));
    let status = "DOWNLOADING";
    const queueDocs = (await transaction.get(queueRef(id).orderBy("position", "asc").limit(MAX_QUEUE_SIZE))).docs;
    if (eligible.every(participantUid => record(downloads[participantUid]).readyForPlayback === true)) {
      const track = { ...(data.track as PlaybackItem["track"]), checksum };
      const alreadyShared = queueDocs.some(doc => doc.get("track")?.sharedTrackId === track.sharedTrackId);
      status = alreadyShared || addTrackAfterCurrent(transaction, id, sessionData, queueDocs, track, uid) ? "READY" : "FAILED";
      if (status === "READY") transaction.update(session, {
        queueRevision: Number(sessionData.queueRevision ?? 0) + 1,
        revision: Number(sessionData.revision ?? 0) + 1
      });
    }
    transaction.update(proposal, {
      status,
      uploadStatus: "COMPLETE",
      storagePath,
      checksum,
      fileSize,
      contentType,
      downloads,
      transferExpiresAt: Timestamp.fromMillis(Date.now() + TRANSFER_LIFETIME_MS),
      ...(status === "READY" ? { completedAt: FieldValue.serverTimestamp(), finalResult: "APPROVED" } : {})
    });
    return { eligible, track: data.track as PlaybackItem["track"], status };
  });
  await pushRecommendation(id, transactionResult.eligible, {
    kind: "jam_download_started",
    recommendationId: proposalId,
    title: "Jam download started",
    body: `${transactionResult.track.title} is being shared with the group.`
  }).catch(() => undefined);
  return { status: transactionResult.status, checksum, fileSize };
});

export const updateJamDownloadState = onCall(CALLABLE_OPTIONS, async request => {
  const uid = requireUid(request);
  const input = record(request.data);
  const id = safeSessionId(input.sessionId);
  const proposalId = requiredString(input.recommendationId, "Recommendation ID is missing.", 64);
  const requestedStatus = requiredString(input.status, "Download status is missing.", 32).toUpperCase();
  const allowedStatuses = new Set(["DOWNLOADING", "VERIFYING_FILE", "DOWNLOAD_FAILED", "READY_FOR_PLAYBACK", "WAITING_TO_DOWNLOAD"]);
  if (!allowedStatuses.has(requestedStatus)) throw new HttpsError("invalid-argument", "Download status is invalid.");
  const progress = Math.max(0, Math.min(100, Math.floor(Number(input.progress ?? 0))));
  const proposal = recommendationRef(id, proposalId);
  const session = sessionRef(id);
  const member = participantRef(id, uid);
  const result = await db.runTransaction(async transaction => {
    const [sessionSnapshot, memberSnapshot, proposalSnapshot] = await Promise.all([
      transaction.get(session), transaction.get(member), transaction.get(proposal)
    ]);
    const sessionData = sessionSnapshot.data();
    assertActiveSession(sessionData);
    requireMember(memberSnapshot.data());
    if (!proposalSnapshot.exists) throw new HttpsError("not-found", "This download no longer exists.");
    const data = proposalSnapshot.data()!;
    if (data.recommendationType !== "LOCAL_SONG" || !["DOWNLOADING", "FAILED"].includes(String(data.status))) {
      throw new HttpsError("failed-precondition", "This transfer is no longer active.");
    }
    const eligible = data.eligibleUids as string[];
    if (!eligible.includes(uid)) throw new HttpsError("permission-denied", "You are not part of this transfer.");
    const downloads = { ...record(data.downloads) } as Record<string, Data>;
    const previous = record(downloads[uid]);
    if (requestedStatus === "READY_FOR_PLAYBACK") {
      if (input.checksum !== data.checksum || input.checksumVerified !== true || input.locallyAvailable !== true) {
        throw new HttpsError("failed-precondition", "The downloaded song did not pass file verification.");
      }
    }
    const now = Date.now();
    const lastProgressWrite = Number(previous.progressUpdatedAtMs ?? 0);
    if (["DOWNLOADING", "VERIFYING_FILE"].includes(requestedStatus) && now - lastProgressWrite < 4_000 && Math.abs(Number(previous.progress ?? 0) - progress) < 5) {
      return { status: String(data.status), eligible, track: data.track as PlaybackItem["track"], notify: false };
    }
    downloads[uid] = {
      ...previous,
      status: requestedStatus,
      progress,
      checksumVerified: requestedStatus === "READY_FOR_PLAYBACK" ? true : previous.checksumVerified === true,
      locallyAvailable: requestedStatus === "READY_FOR_PLAYBACK" ? true : previous.locallyAvailable === true,
      readyForPlayback: requestedStatus === "READY_FOR_PLAYBACK",
      progressUpdatedAtMs: now,
      updatedAt: FieldValue.serverTimestamp(),
      error: requestedStatus === "DOWNLOAD_FAILED" ? String(input.error ?? "Download failed.").slice(0, 160) : ""
    };
    const allReady = eligible.every(participantUid => downloads[participantUid]?.readyForPlayback === true);
    let status = String(data.status);
    let notify = false;
    const queueDocs = allReady
      ? (await transaction.get(queueRef(id).orderBy("position", "asc").limit(MAX_QUEUE_SIZE))).docs
      : [];
    if (allReady && status !== "READY") {
      const track = { ...(data.track as PlaybackItem["track"]), checksum: String(data.checksum) };
      const alreadyShared = queueDocs.some(doc => doc.get("track")?.sharedTrackId === track.sharedTrackId);
      status = alreadyShared || addTrackAfterCurrent(transaction, id, sessionData, queueDocs, track, String(data.proposerUid)) ? "READY" : "FAILED";
      if (status === "READY") {
        transaction.update(session, {
          queueRevision: Number(sessionData.queueRevision ?? 0) + 1,
          revision: Number(sessionData.revision ?? 0) + 1
        });
        notify = true;
      }
    }
    transaction.update(proposal, {
      downloads,
      status,
      ...(status === "READY" ? { completedAt: FieldValue.serverTimestamp(), finalResult: "APPROVED" } : {})
    });
    return { status, eligible, track: data.track as PlaybackItem["track"], notify, downloadFailed: requestedStatus === "DOWNLOAD_FAILED" };
  });
  if (result.downloadFailed) await pushRecommendation(id, result.eligible, {
    kind: "jam_download_failed",
    recommendationId: proposalId,
    proposerUid: "",
    title: "Jam download needs attention",
    body: `${result.track.title} could not be verified on one device. The group can retry.`
  }).catch(() => undefined);
  if (result.notify) await pushRecommendation(id, result.eligible, {
    kind: "jam_download_ready",
    recommendationId: proposalId,
    title: "Song ready to play",
    body: `${result.track.title} is verified on every Jam device.`
  }).catch(() => undefined);
  return { status: result.status };
});

export const retryJamDownload = onCall(CALLABLE_OPTIONS, async request => {
  const uid = requireUid(request);
  const input = record(request.data);
  const id = safeSessionId(input.sessionId);
  const proposalId = requiredString(input.recommendationId, "Recommendation ID is missing.", 64);
  const proposal = recommendationRef(id, proposalId);
  await db.runTransaction(async transaction => {
    const [sessionSnapshot, memberSnapshot, proposalSnapshot] = await Promise.all([
      transaction.get(sessionRef(id)), transaction.get(participantRef(id, uid)), transaction.get(proposal)
    ]);
    assertActiveSession(sessionSnapshot.data());
    requireMember(memberSnapshot.data());
    if (!proposalSnapshot.exists || !["DOWNLOADING", "FAILED"].includes(String(proposalSnapshot.get("status")))) {
      throw new HttpsError("failed-precondition", "This transfer cannot be retried.");
    }
    const eligible = proposalSnapshot.get("eligibleUids") as string[];
    if (!eligible.includes(uid)) throw new HttpsError("permission-denied", "You are not part of this transfer.");
    const path = proposalSnapshot.get("storagePath") as string | undefined;
    const expiresAt = proposalSnapshot.get("transferExpiresAt") as Timestamp | undefined;
    if (!path || !expiresAt || expiresAt.toMillis() <= Date.now()) throw new HttpsError("deadline-exceeded", "This temporary transfer has expired.");
    const downloads = { ...record(proposalSnapshot.get("downloads")) } as Record<string, Data>;
    downloads[uid] = { ...record(downloads[uid]), status: "WAITING_TO_DOWNLOAD", progress: 0, error: "", readyForPlayback: false };
    transaction.update(proposal, { status: "DOWNLOADING", downloads });
  });
  return { ok: true };
});

export const submitJamPlaybackCommand = onCall(CALLABLE_OPTIONS, async request => {
  const uid = requireUid(request);
  await rateLimit(uid, "playback", 90, 60_000);
  const input = record(request.data);
  const id = safeSessionId(input.sessionId);
  const type = requiredString(input.commandType, "Playback command is missing.", 24);
  const permission = permissionForCommand(type);
    const commandId = requiredString(input.commandId, "Command ID is missing.", 64);
  if (!/^[A-Za-z0-9-]{16,64}$/.test(commandId)) throw new HttpsError("invalid-argument", "Command ID is invalid.");
  const session = sessionRef(id);
  const member = participantRef(id, uid);
  const command = session.collection("commands").doc(commandId);
  const query = queueRef(id).orderBy("position", "asc").limit(MAX_QUEUE_SIZE);
  return db.runTransaction(async transaction => {
    const [sessionSnapshot, memberSnapshot, queueSnapshot, commandSnapshot] = await Promise.all([
      transaction.get(session), transaction.get(member), transaction.get(query), transaction.get(command)
    ]);
    const data = sessionSnapshot.data();
    assertActiveSession(data);
    requireMember(memberSnapshot.data());
    const playback = data.playback as PlaybackState;
    if (commandSnapshot.exists || playback.commandId === commandId) throw new HttpsError("already-exists", "This command has already been applied.");
    requirePermission(data, permission, uid);
    const oldRevision = Number(playback.revision ?? 0);
    const nowMs = Date.now();
    let nextPlayback: PlaybackState;
    try {
      nextPlayback = applyPlaybackCommand({
        previous: playback,
        queue: toPlaybackItems(queueSnapshot.docs),
        commandType: type as "PLAY_PAUSE" | "SEEK" | "SKIP_TRACK",
        commandId,
        actorUid: uid,
        nowMs,
        isPlaying: typeof input.isPlaying === "boolean" ? input.isPlaying : undefined,
        positionMs: typeof input.positionMs === "number" ? input.positionMs : undefined,
        step: input.step === -1 ? -1 : 1
      });
    } catch (error) {
      throw new HttpsError("failed-precondition", error instanceof Error ? error.message : "Playback state changed. Try again.");
    }
    const nextRevision = Number(data.revision ?? 0) + 1;
    const { updatedAtMs: _updatedAtMs, ...persistedPlayback } = nextPlayback;
    transaction.update(session, {
      playback: { ...persistedPlayback, updatedAt: FieldValue.serverTimestamp() },
      revision: nextRevision
    });
    transaction.create(command, {
      sessionId: id,
      actorUid: uid,
      commandType: type,
      queueItemId: nextPlayback.queueItemId,
      sharedTrackId: nextPlayback.sharedTrackId,
      positionMs: nextPlayback.positionMs,
      playbackSpeed: nextPlayback.playbackSpeed,
      sessionRevision: nextRevision,
      createdAt: FieldValue.serverTimestamp(),
      expiresAt: Timestamp.fromMillis(nowMs + COMMAND_HISTORY_TTL_MS)
    });
    return {
      ...nextPlayback,
      revision: oldRevision + 1,
      updatedAtMs: nowMs
    };
  });
});

export const closeJam = onCall(CALLABLE_OPTIONS, async request => {
  const uid = requireUid(request);
  const id = safeSessionId(record(request.data).sessionId);
  const session = sessionRef(id);
  return db.runTransaction(async transaction => {
    const snapshot = await transaction.get(session);
    const data = snapshot.data();
    if (!data) throw new HttpsError("not-found", "This Jam no longer exists.");
    requireHost(data, uid);
    if (data.status === "CLOSED") return { ok: true };
    transaction.update(session, { status: "CLOSED", revision: Number(data.revision ?? 0) + 1 });
    const hash = data.inviteCodeHash as string | undefined;
    if (hash) transaction.set(inviteRef(hash), { active: false }, { merge: true });
    return { ok: true };
  });
});

export const revokeJamInvite = onCall(CALLABLE_OPTIONS, async request => {
  const uid = requireUid(request);
  const id = safeSessionId(record(request.data).sessionId);
  const session = sessionRef(id);
  return db.runTransaction(async transaction => {
    const snapshot = await transaction.get(session);
    const data = snapshot.data();
    if (!data) throw new HttpsError("not-found", "This Jam no longer exists.");
    requireHost(data, uid);
    const hash = data.inviteCodeHash as string | undefined;
    if (hash) transaction.set(inviteRef(hash), { active: false }, { merge: true });
    return { ok: true };
  });
});

export const transferJamHost = onCall(CALLABLE_OPTIONS, async request => {
  const uid = requireUid(request);
  const input = record(request.data);
  const id = safeSessionId(input.sessionId);
  const participantUid = requiredString(input.participantUid, "Choose a participant to become host.", 128);
  const session = sessionRef(id);
  const oldHost = participantRef(id, uid);
  const nextHost = participantRef(id, participantUid);
  return db.runTransaction(async transaction => {
    const [sessionSnapshot, oldSnapshot, nextSnapshot] = await Promise.all([
      transaction.get(session), transaction.get(oldHost), transaction.get(nextHost)
    ]);
    const data = sessionSnapshot.data();
    assertActiveSession(data);
    requireHost(data, uid);
    requireMember(oldSnapshot.data());
    requireMember(nextSnapshot.data());
    transaction.update(session, { hostUid: participantUid, revision: Number(data.revision ?? 0) + 1 });
    transaction.update(oldHost, { role: "PARTICIPANT" });
    transaction.update(nextHost, { role: "HOST" });
    return { ok: true };
  });
});

export const leaveJam = onCall(CALLABLE_OPTIONS, async request => {
  const uid = requireUid(request);
  const id = safeSessionId(record(request.data).sessionId);
  const session = sessionRef(id);
  const member = participantRef(id, uid);
  return db.runTransaction(async transaction => {
    const [sessionSnapshot, memberSnapshot, peopleSnapshot, activeTransfers] = await Promise.all([
      transaction.get(session),
      transaction.get(member),
      transaction.get(session.collection("participants").orderBy("joinedAt", "asc")),
      transaction.get(session.collection("recommendations").where("status", "in", ["APPROVED_PENDING_UPLOAD", "UPLOADING", "DOWNLOADING"]).limit(100))
    ]);
    const data = sessionSnapshot.data();
    if (!data || !memberSnapshot.exists) return { ok: true };
    failTransfersForDepartingParticipants(transaction, activeTransfers.docs, new Set([uid]));
    const members = peopleSnapshot.docs.filter(doc => doc.id !== uid);
    const oldHostUid = String(data.hostUid);
    const inviteHash = data.inviteCodeHash as string | undefined;
    transaction.delete(member);
    const revision = Number(data.revision ?? 0) + 1;
    if (members.length === 0) {
      transaction.update(session, { status: "CLOSED", participantCount: 0, revision });
      if (inviteHash) transaction.set(inviteRef(inviteHash), { active: false }, { merge: true });
      return { ok: true };
    }
    const updates: Data = { participantCount: members.length, revision };
    if (oldHostUid === uid) {
      const replacement = members[0];
      updates.hostUid = replacement.id;
      transaction.update(replacement.ref, { role: "HOST" });
    }
    const currentPermissions = data.permissions as Permissions;
    const permissions = { ...currentPermissions };
    (['playPause', 'seek', 'skipTrack', 'addToQueue', 'manageQueue'] as PermissionKey[]).forEach(key => {
      const rule = currentPermissions[key];
      permissions[key] = { ...rule, selectedUids: (rule.selectedUids ?? []).filter(selected => selected !== uid) };
    });
    updates.permissions = permissions;
    transaction.update(session, updates);
    return { ok: true };
  });
});

export const expireInactiveJams = onSchedule({ schedule: "every 15 minutes", region: REGION }, async () => {
  const now = Date.now();
  let cursor: QueryDocumentSnapshot | undefined;
  while (true) {
    let query = db.collection("jamSessions").where("status", "==", "ACTIVE")
      .orderBy(FieldPath.documentId()).limit(100);
    if (cursor) query = query.startAfter(cursor);
    const active = await query.get();
    if (active.empty) break;
    for (const doc of active.docs) {
      const session = doc.ref;
      await db.runTransaction(async transaction => {
      const currentSnapshot = await transaction.get(session);
      const current = currentSnapshot.data();
      if (!sessionIsActive(current)) return;
      const [members, activeTransfers] = await Promise.all([
        transaction.get(session.collection("participants").orderBy("joinedAt", "asc")),
        transaction.get(session.collection("recommendations").where("status", "in", ["APPROVED_PENDING_UPLOAD", "UPLOADING", "DOWNLOADING"]).limit(100))
      ]);
      const fresh = members.docs.filter(member => now - ((member.get("lastSeenAt") as Timestamp | undefined)?.toMillis() ?? 0) <= PRESENCE_STALE_MS);
      const staleUids = new Set(members.docs.filter(member => !fresh.some(item => item.id === member.id)).map(member => member.id));
      if (staleUids.size > 0) failTransfersForDepartingParticipants(transaction, activeTransfers.docs, staleUids);
      const hostUid = String(current.hostUid);
      const staleHost = !fresh.some(member => member.id === hostUid);
      if (fresh.length === 0) {
        transaction.update(session, { status: "CLOSED", participantCount: 0, revision: Number(current.revision ?? 0) + 1 });
        const hash = current.inviteCodeHash as string | undefined;
        if (hash) transaction.set(inviteRef(hash), { active: false }, { merge: true });
        members.docs.forEach(member => transaction.delete(member.ref));
        return;
      }
      const stale = members.docs.filter(member => !fresh.some(item => item.id === member.id));
      if (stale.length === 0) return;
      stale.forEach(member => transaction.delete(member.ref));
      const replacement = fresh[0];
      if (staleHost) transaction.update(replacement.ref, { role: "HOST" });
      const permissions = record(current.permissions);
      for (const [key, ruleValue] of Object.entries(permissions)) {
        const rule = record(ruleValue);
        if (Array.isArray(rule.selectedUids)) {
          rule.selectedUids = rule.selectedUids.filter((selected: unknown) => typeof selected === "string" && fresh.some(member => member.id === selected));
          permissions[key] = rule;
        }
      }
      transaction.update(session, {
        participantCount: fresh.length,
        permissions,
        ...(staleHost ? { hostUid: replacement.id } : {}),
        revision: Number(current.revision ?? 0) + 1
      });
      });
    }
    cursor = active.docs[active.docs.length - 1];
    if (active.size < 100) break;
  }
});

export const pruneJamCommandHistory = onSchedule({ schedule: "every 60 minutes", region: REGION }, async () => {
  const cutoff = Timestamp.now();
  while (true) {
    const expired = await db.collectionGroup("commands")
      .where("expiresAt", "<=", cutoff)
      .orderBy("expiresAt", "asc")
      .limit(400)
      .get();
    if (expired.empty) return;
    const batch = db.batch();
    expired.docs.forEach(command => batch.delete(command.ref));
    await batch.commit();
    if (expired.size < 400) return;
  }
});

export const expireJamRecommendations = onSchedule({ schedule: "every 1 minutes", region: REGION }, async () => {
  const now = Timestamp.now();
  const expiredVotes = await db.collectionGroup("recommendations")
    .where("status", "==", "VOTING")
    .where("deadlineAt", "<=", now)
    .limit(200)
    .get();
  for (const proposal of expiredVotes.docs) {
    await proposal.ref.update({ status: "EXPIRED", finalResult: "EXPIRED", completedAt: FieldValue.serverTimestamp() });
    const sessionId = proposal.get("sessionId") as string;
    const uids = proposal.get("eligibleUids") as string[];
    await pushRecommendation(sessionId, uids, {
      kind: "jam_recommendation_result",
      recommendationId: proposal.id,
      title: "Jam recommendation expired",
      body: `${String(proposal.get("track.title") ?? "Song recommendation")} received no majority before the deadline.`
    }).catch(() => undefined);
  }

  const expiredTransfers = await db.collectionGroup("recommendations")
    .where("transferExpiresAt", "<=", now)
    .limit(200)
    .get();
  for (const proposal of expiredTransfers.docs) {
    const path = proposal.get("storagePath") as string | undefined;
    const active = ["DOWNLOADING", "UPLOADING"].includes(String(proposal.get("status")));
    await db.runTransaction(async transaction => {
      const current = (await transaction.get(proposal.ref)).data();
      if (!current || current.storageCleanedAt || !current.transferExpiresAt || (current.transferExpiresAt as Timestamp).toMillis() > Date.now()) return;
      transaction.update(proposal.ref, {
        ...(active ? { status: "FAILED", finalResult: "EXPIRED", failureReason: "Transfer expired." } : {}),
        storagePath: "",
        transferExpiresAt: FieldValue.delete(),
        storageCleanedAt: FieldValue.serverTimestamp()
      });
    });
    if (path) await getStorage().bucket().file(path).delete({ ignoreNotFound: true }).catch(() => undefined);
  }
});
