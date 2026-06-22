import type { SyncEnvelopeV1, SyncPayloadV1 } from "./types";
import { parseSyncPayload } from "./types";

const encoder = new TextEncoder();
const decoder = new TextDecoder();
const AAD = encoder.encode("easy-bc-sync-envelope-v1");
const HKDF_INFO = encoder.encode("easy-bc-cloud-content-key-v1");

export function bytesToBase64Url(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/u, "");
}

export function base64UrlToBytes(value: string): Uint8Array {
  const base64 = value.replaceAll("-", "+").replaceAll("_", "/");
  const padded = base64.padEnd(Math.ceil(base64.length / 4) * 4, "=");
  const binary = atob(padded);
  return Uint8Array.from(binary, (character) => character.charCodeAt(0));
}

export function randomBytes(length: number): Uint8Array {
  return crypto.getRandomValues(new Uint8Array(length));
}

async function deriveContentKey(prfSecret: Uint8Array, salt: Uint8Array): Promise<CryptoKey> {
  const material = await crypto.subtle.importKey("raw", prfSecret, "HKDF", false, ["deriveKey"]);
  return crypto.subtle.deriveKey(
    { name: "HKDF", hash: "SHA-256", salt, info: HKDF_INFO },
    material,
    { name: "AES-GCM", length: 256 },
    false,
    ["encrypt", "decrypt"],
  );
}

async function transformBytes(
  bytes: Uint8Array,
  stream: CompressionStream | DecompressionStream,
): Promise<Uint8Array> {
  const input = new Blob([bytes as BlobPart]).stream().pipeThrough(stream);
  return new Uint8Array(await new Response(input).arrayBuffer());
}

async function gzip(bytes: Uint8Array): Promise<Uint8Array> {
  return transformBytes(bytes, new CompressionStream("gzip"));
}

async function gunzip(bytes: Uint8Array): Promise<Uint8Array> {
  return transformBytes(bytes, new DecompressionStream("gzip"));
}

export async function encryptSyncPayload(
  payload: SyncPayloadV1,
  prfSecret: Uint8Array,
  credentialId: string,
  rpId: string,
  prfInput: Uint8Array,
  kdfSalt: Uint8Array,
): Promise<SyncEnvelopeV1> {
  const nonce = randomBytes(12);
  const key = await deriveContentKey(prfSecret, kdfSalt);
  const plaintext = encoder.encode(JSON.stringify(payload));
  const compressed = await gzip(plaintext);
  const useCompression = compressed.length < plaintext.length;
  const ciphertext = await crypto.subtle.encrypt(
    { name: "AES-GCM", iv: nonce, additionalData: AAD, tagLength: 128 },
    key,
    useCompression ? compressed : plaintext,
  );
  return {
    schemaVersion: 1,
    algorithm: "AES-256-GCM+HKDF-SHA-256",
    ...(useCompression ? { compression: "gzip" as const } : {}),
    credentialId,
    rpId,
    prfInput: bytesToBase64Url(prfInput),
    kdfSalt: bytesToBase64Url(kdfSalt),
    nonce: bytesToBase64Url(nonce),
    ciphertext: bytesToBase64Url(new Uint8Array(ciphertext)),
    updatedAt: payload.exportedAt,
  };
}

export async function decryptSyncPayload(
  envelope: SyncEnvelopeV1,
  prfSecret: Uint8Array,
): Promise<SyncPayloadV1> {
  const key = await deriveContentKey(prfSecret, base64UrlToBytes(envelope.kdfSalt));
  try {
    const decrypted = new Uint8Array(await crypto.subtle.decrypt(
      {
        name: "AES-GCM",
        iv: base64UrlToBytes(envelope.nonce),
        additionalData: AAD,
        tagLength: 128,
      },
      key,
      base64UrlToBytes(envelope.ciphertext),
    ));
    const plaintext = envelope.compression === "gzip" ? await gunzip(decrypted) : decrypted;
    return parseSyncPayload(decoder.decode(plaintext));
  } catch {
    throw new Error("This passkey could not decrypt the EasyBC snapshot.");
  }
}

export function parseSyncEnvelope(value: string): SyncEnvelopeV1 {
  const parsed = JSON.parse(value) as Partial<SyncEnvelopeV1>;
  if (
    parsed.schemaVersion !== 1 ||
    parsed.algorithm !== "AES-256-GCM+HKDF-SHA-256" ||
    (parsed.compression !== undefined && parsed.compression !== "gzip") ||
    !parsed.credentialId ||
    !parsed.rpId ||
    !parsed.prfInput ||
    !parsed.kdfSalt ||
    !parsed.nonce ||
    !parsed.ciphertext
  ) {
    throw new Error("The Drive file is not a supported EasyBC encrypted snapshot.");
  }
  return parsed as SyncEnvelopeV1;
}
