import { base64UrlToBytes, bytesToBase64Url, randomBytes } from "./crypto";

type PrfResults = {
  prf?: {
    enabled?: boolean;
    results?: { first?: ArrayBuffer };
  };
};

export type PasskeyMaterial = {
  credentialId: string;
  prfInput: Uint8Array;
  kdfSalt: Uint8Array;
  secret: Uint8Array;
  rpId: string;
};

function publicKeyCredential(value: Credential | null): PublicKeyCredential {
  if (!(value instanceof PublicKeyCredential)) {
    throw new Error("Passkey creation was cancelled or unavailable.");
  }
  return value;
}

function prfSecret(credential: PublicKeyCredential): Uint8Array | null {
  const result = credential.getClientExtensionResults() as AuthenticationExtensionsClientOutputs & PrfResults;
  const first = result.prf?.results?.first;
  return first ? new Uint8Array(first) : null;
}

function prfExtensions(prfInput: Uint8Array): AuthenticationExtensionsClientInputs {
  return {
    prf: { eval: { first: prfInput.buffer.slice(0) } },
  } as AuthenticationExtensionsClientInputs;
}

export function currentRpId(): string {
  return window.location.hostname;
}

export function passkeysSupported(): boolean {
  return window.isSecureContext && "PublicKeyCredential" in window && Boolean(navigator.credentials);
}

async function evaluatePrf(
  credentialId: Uint8Array,
  prfInput: Uint8Array,
  rpId: string,
): Promise<Uint8Array> {
  const credential = publicKeyCredential(
    await navigator.credentials.get({
      publicKey: {
        challenge: randomBytes(32),
        rpId,
        allowCredentials: [{ type: "public-key", id: credentialId }],
        userVerification: "required",
        timeout: 60_000,
        extensions: prfExtensions(prfInput),
      },
    }),
  );
  const secret = prfSecret(credential);
  if (!secret) {
    throw new Error("This browser or passkey provider did not return a PRF secret.");
  }
  return secret;
}

export async function createSyncPasskey(): Promise<PasskeyMaterial> {
  if (!passkeysSupported()) {
    throw new Error("Passkeys with encryption support require a secure, compatible browser.");
  }
  const rpId = currentRpId();
  const prfInput = randomBytes(32);
  const kdfSalt = randomBytes(32);
  const created = publicKeyCredential(
    await navigator.credentials.create({
      publicKey: {
        rp: { id: rpId, name: "EasyBC" },
        user: {
          id: randomBytes(32),
          name: "encrypted-sync",
          displayName: "EasyBC encrypted sync",
        },
        challenge: randomBytes(32),
        pubKeyCredParams: [{ type: "public-key", alg: -7 }],
        authenticatorSelection: {
          residentKey: "required",
          requireResidentKey: true,
          userVerification: "required",
        },
        timeout: 60_000,
        attestation: "none",
        extensions: prfExtensions(prfInput),
      },
    }),
  );
  const credentialId = new Uint8Array(created.rawId);
  const secret = prfSecret(created) ?? await evaluatePrf(credentialId, prfInput, rpId);
  return {
    credentialId: bytesToBase64Url(credentialId),
    prfInput,
    kdfSalt,
    secret,
    rpId,
  };
}

export async function unlockSyncPasskey(
  credentialId: string,
  prfInput: string,
  rpId = currentRpId(),
): Promise<Uint8Array> {
  if (!passkeysSupported()) {
    throw new Error("Passkeys with encryption support require a secure, compatible browser.");
  }
  return evaluatePrf(base64UrlToBytes(credentialId), base64UrlToBytes(prfInput), rpId);
}
