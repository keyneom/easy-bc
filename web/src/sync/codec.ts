import type { SyncCodec } from "@keyneom/sync-kit/core";
import {
  mergeSyncPayloads,
  parseSyncPayload,
  type SyncPayloadV1,
} from "./types";

export function syncPayloadFingerprint(payload: SyncPayloadV1): string {
  const { exportedAt: _exportedAt, ...stablePayload } = payload;
  return JSON.stringify(stablePayload);
}

export const easyBcSyncCodec: SyncCodec<SyncPayloadV1> = {
  serialize: (value) => value,
  parse: parseSyncPayload,
  // Preserve EasyBC's established remote-first tie-breaking semantics.
  merge: (local, remote) => mergeSyncPayloads(remote, local),
  fingerprint: syncPayloadFingerprint,
  updatedAt: (value) => value.exportedAt,
};
