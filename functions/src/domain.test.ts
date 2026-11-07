import test from "node:test";
import assert from "node:assert/strict";
import {
  applyPlaybackCommand,
  canPerform,
  DEFAULT_PERMISSIONS,
  invitationDigest,
  makeSharedTrackId,
  normalizeInviteCode,
  recommendationDecision,
  requiredApprovals,
  validatePermissions,
  validateTrack
} from "./domain";

test("invitation codes normalize and hash without exposing the raw code", () => {
  const normalized = normalizeInviteCode("ab-cd 2345efghjkmn");
  assert.equal(normalized, "ABCD2345EFGHJKMN");
  assert.equal(invitationDigest(normalized), invitationDigest("ABCD2345EFGHJKMN"));
  assert.match(invitationDigest(normalized), /^[a-f0-9]{64}$/);
});

test("shared identity uses only normalized track metadata and duration", () => {
  const first = makeSharedTrackId({ title: "  Song  Name ", artist: "Band", album: "Record", durationMs: 182_500 });
  const second = makeSharedTrackId({ title: "song name", artist: "band", album: "record", durationMs: 183_100 });
  assert.equal(first, second);
  assert.notEqual(first, makeSharedTrackId({ title: "Song Name", artist: "Another Band", album: "Record", durationMs: 182_500 }));
  assert.match(first, /^[a-f0-9]{64}$/);
});

test("track metadata is validated and the server derives its shared identity", () => {
  const track = validateTrack({ title: "Track", artist: "Artist", album: "Album", durationMs: 180_000 });
  assert.match(track.sharedTrackId, /^[a-f0-9]{64}$/);
  assert.throws(() => validateTrack({ title: "Track", artist: "Artist", album: "Album", durationMs: 180_000, sharedTrackId: "forged" }));
  assert.throws(() => validateTrack({ title: "", artist: "Artist", durationMs: 180_000 }));
  assert.throws(() => validateTrack({ title: "Track", artist: "Artist", durationMs: 0 }));
});

test("host retains permission and queue management can be explicitly opened to everyone", () => {
  const rules = validatePermissions({
    ...DEFAULT_PERMISSIONS,
    playPause: { mode: "SELECTED", selectedUids: [] }
  });
  assert.equal(canPerform(rules, "playPause", "host", "host"), true);
  assert.equal(canPerform(rules, "playPause", "other", "host"), false);
  const openQueue = validatePermissions({ ...DEFAULT_PERMISSIONS, manageQueue: { mode: "EVERYONE", selectedUids: [] } });
  assert.equal(canPerform(openQueue, "manageQueue", "other", "host"), true);
  assert.equal(openQueue.allowLocalSongSharing, false);
  assert.equal(openQueue.allowSharedLibraryRecommendations, true);
});

test("song sharing and shared-library recommendations stay independent of queue permissions", () => {
  const permissions = validatePermissions({
    ...DEFAULT_PERMISSIONS,
    manageQueue: { mode: "EVERYONE", selectedUids: [] },
    allowLocalSongSharing: true,
    allowSharedLibraryRecommendations: false
  });
  assert.equal(permissions.manageQueue.mode, "EVERYONE");
  assert.equal(permissions.allowLocalSongSharing, true);
  assert.equal(permissions.allowSharedLibraryRecommendations, false);
});

test("shared-library votes require a strict majority from the fixed participant snapshot", () => {
  assert.equal(requiredApprovals(8, "SHARED_LIBRARY"), 5);
  assert.equal(requiredApprovals(10, "SHARED_LIBRARY"), 6);
  assert.equal(recommendationDecision({ type: "SHARED_LIBRARY", approvals: 4, rejections: 0, pending: 4, required: 5 }), "VOTING");
  assert.equal(recommendationDecision({ type: "SHARED_LIBRARY", approvals: 5, rejections: 0, pending: 3, required: 5 }), "READY");
  assert.equal(recommendationDecision({ type: "SHARED_LIBRARY", approvals: 1, rejections: 4, pending: 3, required: 5 }), "REJECTED");
  assert.equal(recommendationDecision({ type: "SHARED_LIBRARY", approvals: 4, rejections: 0, pending: 4, required: 5, deadlineExpired: true }), "EXPIRED");
});

test("local-song transfer approval requires every snapshot participant to accept", () => {
  assert.equal(requiredApprovals(8, "LOCAL_SONG"), 8);
  assert.equal(recommendationDecision({ type: "LOCAL_SONG", approvals: 7, rejections: 0, pending: 1, required: 8 }), "VOTING");
  assert.equal(recommendationDecision({ type: "LOCAL_SONG", approvals: 8, rejections: 0, pending: 0, required: 8 }), "APPROVED_PENDING_UPLOAD");
  assert.equal(recommendationDecision({ type: "LOCAL_SONG", approvals: 7, rejections: 1, pending: 0, required: 8 }), "REJECTED");
  assert.equal(recommendationDecision({ type: "LOCAL_SONG", approvals: 7, rejections: 0, pending: 1, required: 8, deadlineExpired: true }), "EXPIRED");
});

test("playback commands advance one shared queue position and stop at the end", () => {
  const queue = [
    { id: "a", position: 0, track: { sharedTrackId: "ta", title: "A", artist: "X", album: "", durationMs: 100_000 } },
    { id: "b", position: 1, track: { sharedTrackId: "tb", title: "B", artist: "X", album: "", durationMs: 100_000 } }
  ];
  const previous = {
    sharedTrackId: "ta", queueItemId: "a", isPlaying: true, positionMs: 10_000, playbackSpeed: 1,
    revision: 4, commandId: "old", commandType: "PLAY_PAUSE", actorUid: "host", updatedAtMs: 1_000
  };
  const next = applyPlaybackCommand({ previous, queue, commandType: "SKIP_TRACK", commandId: "new", actorUid: "host", nowMs: 1_500, step: 1 });
  assert.equal(next.queueItemId, "b");
  assert.equal(next.positionMs, 0);
  const end = applyPlaybackCommand({ previous: next, queue, commandType: "SKIP_TRACK", commandId: "end", actorUid: "host", nowMs: 2_000, step: 1 });
  assert.equal(end.queueItemId, "b");
  assert.equal(end.isPlaying, false);
});
