import type { SharedSyncConfig } from "./sharedSync";
import { idbGet, KV_SYNC_STATE } from "../idbStore";
import {
  buildLocalSyncPayload,
  forgetSyncState,
  runEncryptedSyncOperation,
} from "./sessionSync";
import type { WasmOptions } from "../App";
import type { PersistedSession } from "../sessionUtils";
import type { PeriodRecord } from "../tracker/types";
import { buildSharedSyncPayload, extractSharedPayload } from "./sharedTypes";
import { setupSharedSync } from "./sharedSync";
import { isSyncSnapshotMissing } from "./syncErrors";

export async function migrateLegacyEncryptedSync(input: {
  config: SharedSyncConfig;
  rpId: string;
  clientId: string;
  options: WasmOptions;
  periodRecords: PeriodRecord[];
  session: PersistedSession;
}): Promise<void> {
  let sharedPayload = buildSharedSyncPayload(input.options, input.periodRecords, input.session);
  const legacyState = await idbGet(KV_SYNC_STATE);
  if (legacyState) {
    const localSnapshot = buildLocalSyncPayload(input.options, input.periodRecords, input.session);
    try {
      const enabled = await runEncryptedSyncOperation({
        operation: "enable",
        clientId: input.clientId,
        rpId: input.rpId,
        local: localSnapshot,
      });
      if (enabled.operation === "delete" || !enabled.payload) {
        throw new Error("Could not unlock the legacy encrypted snapshot for migration.");
      }
      sharedPayload = extractSharedPayload(enabled.payload);
    } catch (error) {
      if (isSyncSnapshotMissing(error)) {
        await forgetSyncState();
      } else {
        throw error;
      }
    }
  }
  await setupSharedSync(input.config, sharedPayload);
  await forgetSyncState();
}
