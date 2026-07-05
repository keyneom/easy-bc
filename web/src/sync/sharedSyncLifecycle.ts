import { bindSharingPoll } from "@keyneom/sync-kit/sharing/lifecycle";
import type { SharingNotificationEvent } from "@keyneom/sync-kit/sharing";
import {
  createSharingChangeDetectorForActiveProfile,
  loadSharingSyncCheckpoint,
  saveSharingSyncCheckpoint,
  type SharedSyncConfig,
} from "./sharedSync";

export type EasyBcSharingPollOptions = {
  config: SharedSyncConfig;
  onEvents: (events: SharingNotificationEvent[]) => void | Promise<void>;
  intervalMs?: number;
};

/**
 * Tier A metadata polling for shared encrypted backups. Uses sync-kit's
 * foreground poll binding plus an IndexedDB checkpoint and cached OAuth tokens.
 */
export async function bindEasyBcSharingPoll(options: EasyBcSharingPollOptions) {
  const detector = await createSharingChangeDetectorForActiveProfile(options.config);
  if (!detector) return null;
  const checkpoint = await loadSharingSyncCheckpoint();
  return bindSharingPoll(
    detector,
    checkpoint,
    async (result) => {
      await saveSharingSyncCheckpoint(result.checkpoint);
      if (result.events.length > 0) {
        await options.onEvents(result.events);
      }
    },
    {
      intervalMs: options.intervalMs ?? 60_000,
      minimumBackgroundMs: 30_000,
    },
  );
}
