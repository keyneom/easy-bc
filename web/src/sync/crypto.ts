import {
  base64UrlToBytes,
  bytesToBase64Url,
  createV1EnvelopeCrypto,
  createWebCryptoBackend,
  deriveContentKey as deriveProfileContentKey,
  parseSyncEnvelopeV1,
  type SyncEnvelopeV1,
  type V1KeyMetadata,
} from "@keyneom/sync-kit/crypto";
import { easyBcSyncCodec } from "./codec";
import { easyBcV1Profile } from "./profile";
import type { SyncPayloadV1 } from "./types";

export { base64UrlToBytes, bytesToBase64Url };

export const easyBcCryptoBackend = createWebCryptoBackend();
export const easyBcEnvelopeCrypto = createV1EnvelopeCrypto(
  easyBcV1Profile,
  easyBcSyncCodec,
  easyBcCryptoBackend,
);

export function randomBytes(length: number): Uint8Array {
  return easyBcCryptoBackend.randomBytes(length);
}

export function deriveContentKey(
  prfSecret: Uint8Array,
  salt: Uint8Array,
): Promise<CryptoKey> {
  return deriveProfileContentKey(
    easyBcV1Profile,
    prfSecret,
    salt,
    easyBcCryptoBackend,
  );
}

export async function encryptSyncPayload(
  payload: SyncPayloadV1,
  prfSecret: Uint8Array,
  credentialId: string,
  rpId: string,
  prfInput: Uint8Array,
  kdfSalt: Uint8Array,
): Promise<SyncEnvelopeV1> {
  const key = await deriveContentKey(prfSecret, kdfSalt);
  return encryptSyncPayloadWithKey(
    payload,
    key,
    credentialId,
    rpId,
    prfInput,
    kdfSalt,
  );
}

export function encryptSyncPayloadWithKey(
  payload: SyncPayloadV1,
  key: CryptoKey,
  credentialId: string,
  rpId: string,
  prfInput: Uint8Array,
  kdfSalt: Uint8Array,
): Promise<SyncEnvelopeV1> {
  const metadata: V1KeyMetadata = {
    credentialId,
    rpId,
    prfInput,
    kdfSalt,
  };
  return easyBcEnvelopeCrypto.encrypt(payload, key, metadata);
}

export function decryptSyncPayload(
  envelope: SyncEnvelopeV1,
  prfSecret: Uint8Array,
): Promise<SyncPayloadV1> {
  return deriveContentKey(
    prfSecret,
    base64UrlToBytes(envelope.kdfSalt),
  ).then((key) => decryptSyncPayloadWithKey(envelope, key));
}

export function decryptSyncPayloadWithKey(
  envelope: SyncEnvelopeV1,
  key: CryptoKey,
): Promise<SyncPayloadV1> {
  return easyBcEnvelopeCrypto.decrypt(envelope, key);
}

export function parseSyncEnvelope(value: string): SyncEnvelopeV1 {
  return parseSyncEnvelopeV1(value, easyBcV1Profile);
}
