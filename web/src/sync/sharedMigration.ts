import type { SharedSyncConfig } from "./sharedSync";
import {
  buildLocalSyncPayload,
  forgetSyncState,
  runEncryptedSyncOperation,
} from "./sessionSync";
import type { WasmOptions } from "../App";
import type { PersistedSession } from "../sessionUtils";
import type { PeriodRecord } from "../tracker/types";
import { buildSharedSyncPayload } from "./sharedTypes";
import { setupSharedSync } from "./sharedSync";

export async function migrateLegacyEncryptedSync(input: {
  config: SharedSyncConfig;
  rpId: string;
  clientId: string;
  options: WasmOptions;
  periodRecords: PeriodRecord[];
  session: PersistedSession;
}): Promise<void> {
  const localSnapshot = buildLocalSyncPayload(input.options, input.periodRecords, input.session);
  const enabled = await runEncryptedSyncOperation({
    operation: "enable",
    clientId: input.clientId,
    rpId: input.rpId,
    local: localSnapshot,
  });
  if (enabled.operation === "delete" || !enabled.payload) {
    throw new Error("Could not unlock the legacy encrypted snapshot for migration.");
  }
  await setupSharedSync(input.config, buildSharedSyncPayload(input.options, input.periodRecords, input.session));
  await forgetSyncState();
}
