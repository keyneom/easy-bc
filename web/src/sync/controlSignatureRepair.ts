import {
  base64UrlToBytes,
  bytesToBase64Url,
  canonicalAad,
  canonicalJson,
} from "@keyneom/sync-kit/crypto";
import type { SharedBackupControllerCodec } from "@keyneom/sync-kit/sharing/controller";
import {
  createSharingControlCodec,
  verifySharingControlStateV1,
  type SharingControlEventV1,
  type SharingControlStateV1,
} from "@keyneom/sync-kit/sharing/control";
import type { WebCryptoSharingIdentity } from "@keyneom/sync-kit/sharing/web-crypto";

export type LegacyControlSignatureRepair = {
  state: SharingControlStateV1;
  eventId: string;
  previousSignature: string;
};

/**
 * EasyBC-only repair for the confirmed sync-kit rc.15 ordering defect.
 * The owner proves the old target-order signature, then re-attests the exact
 * normalized event. Event ids and migration semantics are never changed.
 */
export async function repairLegacyControlSignature(
  state: SharingControlStateV1,
  identity: WebCryptoSharingIdentity,
  cryptoImplementation: Crypto = globalThis.crypto,
): Promise<LegacyControlSignatureRepair | null> {
  const events = [...state.events].sort(compareEvents);
  const genesis = events[0];
  if (genesis?.type !== "member-upsert") return null;
  const ownerKeyId = genesis.member.publicKey.keyId;
  if (identity.publicKey.keyId !== ownerKeyId) return null;

  const members = new Map<string, typeof genesis.member>();
  const candidates: Array<{ event: SharingControlEventV1; previousSignature: string }> = [];
  for (const event of events) {
    const actor = members.get(event.actorKeyId) ?? (event === genesis ? genesis.member : undefined);
    if (!actor) return null;
    if (await verifyEvent(event, actor.publicKey.signingPublicKey, cryptoImplementation)) {
      if (event.type === "member-upsert") members.set(event.member.publicKey.keyId, event.member);
      continue;
    }
    if (event.type !== "migration-announced" || event.actorKeyId !== ownerKeyId) return null;
    const legacy = targetOrderEvent(event);
    if (!(await verifyEvent(legacy, actor.publicKey.signingPublicKey, cryptoImplementation))) return null;
    candidates.push({ event, previousSignature: event.signature });
  }
  if (candidates.length !== 1) return null;

  const candidate = candidates[0]!;
  const { signature: _oldSignature, ...unsigned } = candidate.event;
  const signature = new Uint8Array(await cryptoImplementation.subtle.sign(
    { name: "ECDSA", hash: "SHA-256" },
    identity.signingPrivateKey,
    buffer(canonicalAad(unsigned)),
  ));
  const repairedEvent = { ...unsigned, signature: bytesToBase64Url(signature) } as SharingControlEventV1;
  const repaired = createSharingControlCodec().parse({
    ...state,
    events: state.events.map((event) => event.eventId === candidate.event.eventId ? repairedEvent : event),
  });
  await verifySharingControlStateV1(repaired, cryptoImplementation, { trustedOwnerKeyId: ownerKeyId });
  return {
    state: repaired,
    eventId: candidate.event.eventId,
    previousSignature: candidate.previousSignature,
  };
}

export function createLegacyControlRepairCodec(
  repair: Pick<LegacyControlSignatureRepair, "eventId" | "previousSignature">,
): SharedBackupControllerCodec<SharingControlStateV1> {
  const standard = createSharingControlCodec();
  return {
    serialize: standard.serialize,
    parse: standard.parse,
    fingerprint: standard.fingerprint,
    merge(local, remote) {
      try {
        return standard.merge(local, remote);
      } catch (error) {
        if (withoutSignatures(local) !== withoutSignatures(remote)) throw error;
        const localEvent = local.events.find((event) => event.eventId === repair.eventId);
        const remoteEvent = remote.events.find((event) => event.eventId === repair.eventId);
        if (!localEvent || !remoteEvent || remoteEvent.signature !== repair.previousSignature ||
            localEvent.signature === repair.previousSignature) {
          throw error;
        }
        return local;
      }
    },
  };
}

function targetOrderEvent(event: Extract<SharingControlEventV1, { type: "migration-announced" }>) {
  const order = new Map(event.migration.targets.map((target, index) => [target.fileId, index]));
  return {
    ...event,
    migration: {
      ...event.migration,
      requiredAcks: event.migration.requiredAcks.map((requirement) => ({
        ...requirement,
        targetFileIds: [...requirement.targetFileIds].sort(
          (left, right) => (order.get(left) ?? Number.MAX_SAFE_INTEGER) -
            (order.get(right) ?? Number.MAX_SAFE_INTEGER),
        ),
      })),
    },
  };
}

async function verifyEvent(
  event: SharingControlEventV1,
  signingPublicKey: string,
  cryptoImplementation: Crypto,
): Promise<boolean> {
  const { signature, ...unsigned } = event;
  const key = await cryptoImplementation.subtle.importKey(
    "raw",
    buffer(base64UrlToBytes(signingPublicKey)),
    { name: "ECDSA", namedCurve: "P-256" },
    false,
    ["verify"],
  );
  return cryptoImplementation.subtle.verify(
    { name: "ECDSA", hash: "SHA-256" },
    key,
    buffer(base64UrlToBytes(signature)),
    buffer(canonicalAad(unsigned)),
  );
}

function withoutSignatures(state: SharingControlStateV1): string {
  return canonicalJson({
    ...state,
    events: state.events.map(({ signature: _signature, ...event }) => event),
  });
}

function compareEvents(left: SharingControlEventV1, right: SharingControlEventV1): number {
  return left.sequence - right.sequence || left.eventId.localeCompare(right.eventId);
}

function buffer(bytes: Uint8Array): ArrayBuffer {
  return bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer;
}
