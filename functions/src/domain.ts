import { createHash, randomBytes } from "node:crypto";

export const MAX_PARTICIPANTS = 10;
export const MAX_QUEUE_SIZE = 200;
export const INVITE_CODE_LENGTH = 16;
export const INVITE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
export const PERMISSION_KEYS = ["playPause", "seek", "skipTrack", "addToQueue", "manageQueue"] as const;
export type PermissionKey = typeof PERMISSION_KEYS[number];
export type AccessMode = "HOST_ONLY" | "EVERYONE" | "SELECTED";
export type RecommendationType = "SHARED_LIBRARY" | "LOCAL_SONG";
export interface PermissionRule { mode: AccessMode; selectedUids: string[] }
export type Permissions = Record<PermissionKey, PermissionRule> & {
  allowLocalSongSharing: boolean;
  allowSharedLibraryRecommendations: boolean;
};

export interface SharedTrack {
  sharedTrackId: string;
  title: string;
  artist: string;
  album: string;
  durationMs: number;
  checksum?: string;
}

export interface PlaybackState {
  sharedTrackId: string;
  queueItemId: string;
  isPlaying: boolean;
  positionMs: number;
  playbackSpeed: number;
  revision: number;
  commandId: string;
  commandType: string;
  actorUid: string;
  updatedAtMs?: number;
  updatedAt?: { toMillis(): number };
  step?: number;
}

export interface PlaybackItem { id: string; position: number; track: SharedTrack }

export function requiredApprovals(participantCount: number, type: RecommendationType): number {
  const count = Math.max(1, Math.floor(participantCount));
  return type === "LOCAL_SONG" ? count : Math.floor(count / 2) + 1;
}

export function recommendationDecision(args: {
  type: RecommendationType;
  approvals: number;
  rejections: number;
  pending: number;
  required: number;
  deadlineExpired?: boolean;
}): "VOTING" | "READY" | "APPROVED_PENDING_UPLOAD" | "REJECTED" | "EXPIRED" {
  const { type, approvals, rejections, pending, required, deadlineExpired = false } = args;
  if (type === "LOCAL_SONG") {
    if (rejections > 0) return "REJECTED";
    if (approvals >= required) return "APPROVED_PENDING_UPLOAD";
    return deadlineExpired ? "EXPIRED" : "VOTING";
  }
  if (approvals >= required) return "READY";
  if (approvals + pending < required) return "REJECTED";
  return deadlineExpired ? "EXPIRED" : "VOTING";
}

export const DEFAULT_PERMISSIONS: Permissions = {
  playPause: { mode: "EVERYONE", selectedUids: [] },
  seek: { mode: "EVERYONE", selectedUids: [] },
  skipTrack: { mode: "EVERYONE", selectedUids: [] },
  addToQueue: { mode: "HOST_ONLY", selectedUids: [] },
  manageQueue: { mode: "HOST_ONLY", selectedUids: [] },
  allowLocalSongSharing: false,
  allowSharedLibraryRecommendations: true
};

export function normalizeInviteCode(value: unknown): string {
  return typeof value === "string" ? value.toUpperCase().replace(/[^A-Z0-9]/g, "").slice(0, INVITE_CODE_LENGTH) : "";
}

export function generateInviteCode(): string {
  const bytes = randomBytes(INVITE_CODE_LENGTH);
  let result = "";
  for (const byte of bytes) result += INVITE_ALPHABET[byte % INVITE_ALPHABET.length];
  return result;
}

export function invitationDigest(code: string): string {
  return createHash("sha256").update(normalizeInviteCode(code), "utf8").digest("hex");
}

export function normalizeMetadata(value: string): string {
  return value.trim().toLowerCase().replace(/\s+/g, " ");
}

export function makeSharedTrackId(track: Pick<SharedTrack, "title" | "artist" | "album" | "durationMs">): string {
  const normalized = [track.title, track.artist, track.album].map(normalizeMetadata).join("\u001f");
  const durationBucket = Math.max(0, Math.floor(track.durationMs / 2_000)).toString();
  return createHash("sha256").update(`${normalized}\u001f${durationBucket}`, "utf8").digest("hex");
}

export function validateTrack(value: unknown): SharedTrack {
  if (!value || typeof value !== "object") throw new Error("Track data is invalid.");
  const input = value as Record<string, unknown>;
  const title = typeof input.title === "string" ? input.title.trim().slice(0, 180) : "";
  const artist = typeof input.artist === "string" ? input.artist.trim().slice(0, 180) : "";
  const album = typeof input.album === "string" ? input.album.trim().slice(0, 180) : "";
  const durationMs = typeof input.durationMs === "number" ? Math.floor(input.durationMs) : 0;
  if (!title || !artist) throw new Error("A track needs a title and artist.");
  if (!Number.isSafeInteger(durationMs) || durationMs < 1_000 || durationMs > 14_400_000) {
    throw new Error("Track duration is outside the supported range.");
  }
  const sharedTrackId = makeSharedTrackId({ title, artist, album, durationMs });
  if (typeof input.sharedTrackId === "string" && input.sharedTrackId !== sharedTrackId) {
    throw new Error("Track identity did not match its shared metadata.");
  }
  const checksum = typeof input.checksum === "string" && /^[a-f0-9]{64}$/i.test(input.checksum) ? input.checksum.toLowerCase() : undefined;
  return { sharedTrackId, title, artist, album, durationMs, ...(checksum ? { checksum } : {}) };
}

export function validatePermissions(value: unknown, participantUids: Set<string> = new Set()): Permissions {
  const input = value && typeof value === "object" ? value as Record<string, unknown> : {};
  const result = {} as Permissions;
  for (const key of PERMISSION_KEYS) {
    const raw = input[key] && typeof input[key] === "object" ? input[key] as Record<string, unknown> : {};
    const rawMode = typeof raw.mode === "string" ? raw.mode : DEFAULT_PERMISSIONS[key].mode;
    if (!(rawMode in { HOST_ONLY: 1, EVERYONE: 1, SELECTED: 1 })) throw new Error(`Invalid ${key} permission mode.`);
    const mode = rawMode as AccessMode;
    const rawUids = Array.isArray(raw.selectedUids) ? raw.selectedUids : [];
    if (rawUids.some(uid => typeof uid !== "string" || !participantUids.has(uid))) {
      throw new Error("Selected permissions can only be assigned to current participants.");
    }
    const selectedUids = [...new Set(rawUids.filter((uid): uid is string => typeof uid === "string" && participantUids.has(uid)))].slice(0, MAX_PARTICIPANTS - 1);
    result[key] = { mode, selectedUids };
  }
  result.allowLocalSongSharing = input.allowLocalSongSharing === true;
  result.allowSharedLibraryRecommendations = input.allowSharedLibraryRecommendations !== false;
  return result;
}

export function canPerform(permissions: Permissions, key: PermissionKey, uid: string, hostUid: string): boolean {
  const rule = permissions[key];
  return uid === hostUid || rule.mode === "EVERYONE" || (rule.mode === "SELECTED" && rule.selectedUids.includes(uid));
}

export function applyPlaybackCommand(args: {
  previous: PlaybackState;
  queue: PlaybackItem[];
  commandType: "PLAY_PAUSE" | "SEEK" | "SKIP_TRACK";
  commandId: string;
  actorUid: string;
  nowMs: number;
  isPlaying?: boolean;
  positionMs?: number;
  step?: number;
}): PlaybackState {
  const { previous, queue, commandType, commandId, actorUid, nowMs } = args;
  let sharedTrackId = previous.sharedTrackId;
  let queueItemId = previous.queueItemId;
  let isPlaying = previous.isPlaying;
  let positionMs = previous.positionMs;
  let step = 0;

  if (commandType === "PLAY_PAUSE") {
    if (typeof args.isPlaying !== "boolean") throw new Error("Play or pause state is required.");
    const previousTime = previous.updatedAt?.toMillis() ?? previous.updatedAtMs ?? nowMs;
    const expected = previous.isPlaying
      ? previous.positionMs + Math.max(0, nowMs - previousTime) * previous.playbackSpeed
      : previous.positionMs;
    if (previous.isPlaying && typeof args.positionMs === "number" && Math.abs(args.positionMs - expected) > 5_000) {
      throw new Error("Playback position changed; synchronize before pausing.");
    }
    positionMs = previous.isPlaying ? Math.round(expected) : previous.positionMs;
    isPlaying = args.isPlaying;
  } else if (commandType === "SEEK") {
    const item = queue.find(candidate => candidate.id === previous.queueItemId);
    if (!item || typeof args.positionMs !== "number" || !Number.isFinite(args.positionMs)) throw new Error("There is no active track to seek.");
    positionMs = Math.min(Math.max(0, Math.floor(args.positionMs)), item.track.durationMs);
  } else {
    if (queue.length === 0) throw new Error("Add a track to the shared queue first.");
    const direction = args.step === -1 ? -1 : 1;
    const currentIndex = queue.findIndex(item => item.id === previous.queueItemId);
    const previousTime = previous.updatedAt?.toMillis() ?? previous.updatedAtMs ?? nowMs;
    const currentPosition = previous.isPlaying
      ? previous.positionMs + Math.max(0, nowMs - previousTime) * previous.playbackSpeed
      : previous.positionMs;
    if (direction < 0 && previous.isPlaying && currentPosition > 3_000) {
      positionMs = 0;
      step = -1;
    } else {
      const targetIndex = currentIndex < 0 ? 0 : currentIndex + direction;
      if (targetIndex < 0) {
        const current = queue[currentIndex];
        if (current) {
          sharedTrackId = current.track.sharedTrackId;
          queueItemId = current.id;
        }
        positionMs = 0;
      } else if (targetIndex >= queue.length) {
        isPlaying = false;
      } else {
        const target = queue[targetIndex];
        sharedTrackId = target.track.sharedTrackId;
        queueItemId = target.id;
        positionMs = 0;
        isPlaying = true;
      }
      step = direction;
    }
  }

  return {
    sharedTrackId,
    queueItemId,
    isPlaying,
    positionMs: Math.max(0, Math.floor(positionMs)),
    playbackSpeed: 1,
    revision: previous.revision + 1,
    commandId,
    commandType,
    actorUid,
    updatedAtMs: nowMs,
    step
  };
}
