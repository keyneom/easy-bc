import type { SharedBackupControllerCodec } from "@keyneom/sync-kit/sharing/controller";
import {
  mergeSharedSyncPayloads,
  parseSharedSyncPayload,
  sharedPayloadFingerprint,
  type SharedSyncPayloadV1,
} from "./sharedTypes";

export const easyBcSharedCodec: SharedBackupControllerCodec<SharedSyncPayloadV1> = {
  serialize: (value) => value,
  parse: parseSharedSyncPayload,
  // The argument swap is load-bearing, not cosmetic. mergeSharedSyncPayloads
  // resolves ties to its FIRST argument, so swapping makes this codec resolve
  // ties to `remote` — and sync-kit's apply guard calls
  // codec.merge(merged, committed), which therefore resolves tie fields to
  // `committed` itself and is trivially satisfied. See reconcileSyncResult's
  // tie test: that and this swap are two independent reasons the guard passes,
  // and losing both turns an equal-timestamp field into a `state` error thrown
  // after the cloud write already succeeded.
  merge: (local, remote) => mergeSharedSyncPayloads(remote, local),
  fingerprint: sharedPayloadFingerprint,
};

export { sharedPayloadFingerprint };
