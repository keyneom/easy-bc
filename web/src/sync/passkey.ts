export function currentRpId(): string {
  return window.location.hostname;
}

export function passkeysSupported(): boolean {
  return (
    window.isSecureContext &&
    "PublicKeyCredential" in window &&
    Boolean(navigator.credentials)
  );
}
