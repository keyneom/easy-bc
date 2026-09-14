import { mergeSharedSyncPayloads, type SharedSyncPayloadV1 } from "./sharedTypes";
import { combineDatasetParts, DATASET_PARTS, projectDatasetPart, type DatasetPart } from "./datasets";

/**
 * Fold a sync result back into whatever local state became while the network
 * round trip was in flight.
 *
 * `syncDataset` merges against a local snapshot captured before the call, so
 * applying its result directly overwrites every edit made in the seconds
 * since — and silently, because the next sync then compares the clobbered
 * value against the cloud and finds nothing left to publish.
 *
 * The payload merge is per-field last-write-wins over `updatedAt`, with
 * tombstones, so it is idempotent: any field the user did not touch during the
 * round trip is identical in `liveLocal` and in the snapshot the result was
 * built from, and folds back to `synced` unchanged. Only a strictly newer
 * local edit survives.
 *
 * Argument order matters. `synced` is first so ties resolve to the published
 * value, matching `easyBcSharedCodec.merge`, which resolves ties to the remote.
 * Reversing it would let two payloads with identical timestamps settle on
 * different values locally and in the cloud, with neither side ever writing.
 */
export function reconcileSyncResult(
  synced: SharedSyncPayloadV1,
  liveLocal: SharedSyncPayloadV1,
  part?: DatasetPart,
): SharedSyncPayloadV1 {
  if (part) {
    // Day logs are LWW rows shared by several files. A partial row must never
    // compete with the complete local day: even a tie would discard siblings.
    // Reconcile within the file, then reassemble it with the other live slices.
    const parts = Object.fromEntries(DATASET_PARTS.map((candidate) => [
      candidate,
      projectDatasetPart(liveLocal, candidate),
    ]));
    parts[part] = mergeSharedSyncPayloads(synced, parts[part]);
    return combineDatasetParts(parts);
  }
  return mergeSharedSyncPayloads(synced, liveLocal);
}
