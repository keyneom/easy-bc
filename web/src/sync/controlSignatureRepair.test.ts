import { describe, expect, it } from "vitest";
import { bytesToBase64Url, canonicalAad } from "@keyneom/sync-kit/crypto";
import {
  verifySharingControlStateV1,
  type SharingControlEventV1,
  type SharingControlStateV1,
} from "@keyneom/sync-kit/sharing/control";
import { createWebCryptoSharingIdentity } from "@keyneom/sync-kit/sharing/web-crypto";
import {
  createLegacyControlRepairCodec,
  repairLegacyControlSignature,
} from "./controlSignatureRepair";

describe("legacy control signature repair", () => {
  it("re-attests only the confirmed target-order migration event", async () => {
    const identity = await createWebCryptoSharingIdentity();
    const genesis = await signed({
      ...base("genesis", 0, identity.publicKey.keyId),
      type: "member-upsert",
      member: { publicKey: identity.publicKey },
    }, identity.signingPrivateKey);
    const migration = {
      migrationId: "migration",
      sourceDatasetIds: ["primary"],
      targets: [
        { datasetId: "primary.g2.plan", fileId: "file-z" },
        { datasetId: "primary.g2.cycle", fileId: "file-a" },
      ],
      requiredAcks: [{ keyId: identity.publicKey.keyId, targetFileIds: ["file-z", "file-a"] }],
      mode: "hard-cutover" as const,
    };
    const unsignedAnnouncement = {
      ...base("announcement", 1, identity.publicKey.keyId),
      type: "migration-announced" as const,
      migration,
    };
    const legacySignature = await signature(unsignedAnnouncement, identity.signingPrivateKey);
    const storedAnnouncement = {
      ...unsignedAnnouncement,
      migration: {
        ...migration,
        requiredAcks: [{ keyId: identity.publicKey.keyId, targetFileIds: ["file-a", "file-z"] }],
      },
      signature: legacySignature,
    } as SharingControlEventV1;
    const state: SharingControlStateV1 = {
      schemaVersion: 1,
      kind: "sync-kit-sharing-control",
      profileId: "profile",
      events: [genesis as SharingControlEventV1, storedAnnouncement],
    };

    await expect(verifySharingControlStateV1(state)).rejects.toMatchObject({ code: "crypto" });
    const repaired = await repairLegacyControlSignature(state, identity);

    expect(repaired?.eventId).toBe("announcement");
    await expect(verifySharingControlStateV1(repaired!.state, crypto, {
      trustedOwnerKeyId: identity.publicKey.keyId,
    })).resolves.toMatchObject({ ownerKeyId: identity.publicKey.keyId });
    const merged = createLegacyControlRepairCodec(repaired!).merge(repaired!.state, state);
    await expect(verifySharingControlStateV1(merged)).resolves.toBeTruthy();
  });
});

function base(eventId: string, sequence: number, actorKeyId: string) {
  return {
    schemaVersion: 1 as const,
    kind: "sync-kit-sharing-control-event" as const,
    eventId,
    profileId: "profile",
    actorKeyId,
    sequence,
    createdAt: "2026-07-13T00:00:00.000Z",
  };
}

async function signed(unsigned: object, privateKey: CryptoKey) {
  return { ...unsigned, signature: await signature(unsigned, privateKey) };
}

async function signature(unsigned: object, privateKey: CryptoKey): Promise<string> {
  const value = await crypto.subtle.sign(
    { name: "ECDSA", hash: "SHA-256" },
    privateKey,
    canonicalAad(unsigned),
  );
  return bytesToBase64Url(new Uint8Array(value));
}
