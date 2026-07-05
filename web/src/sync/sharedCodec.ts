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
  merge: (local, remote) => mergeSharedSyncPayloads(remote, local),
  fingerprint: sharedPayloadFingerprint,
};

export { sharedPayloadFingerprint };
