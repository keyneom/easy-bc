/** Max encoded avatar size after WebP compression (12 KB). */
export const AVATAR_MAX_BYTES = 12 * 1024;

const AVATAR_SIZE = 128;
const INITIAL_QUALITY = 0.7;
const MIN_QUALITY = 0.35;
const QUALITY_STEP = 0.08;

/** Build a displayable data URL from raw base64 WebP (no prefix). */
export function avatarDataUrl(base64: string): string {
  return `data:image/webp;base64,${base64}`;
}

/**
 * Decode the picked file without fetching a URL. `createImageBitmap` reads
 * the blob directly, so it works under the app's strict CSP (`img-src` has
 * no `blob:`); the fallback goes through a data: URL, which the CSP allows.
 */
async function loadImageSource(file: File): Promise<ImageBitmap | HTMLImageElement> {
  if (typeof createImageBitmap === "function") {
    try {
      return await createImageBitmap(file);
    } catch {
      // Fall through to the data-URL path (e.g. unsupported format edge cases).
    }
  }
  const dataUrl = await new Promise<string>((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () =>
      typeof reader.result === "string"
        ? resolve(reader.result)
        : reject(new Error("Could not read that image."));
    reader.onerror = () => reject(new Error("Could not read that image."));
    reader.readAsDataURL(file);
  });
  return new Promise((resolve, reject) => {
    const image = new Image();
    image.onload = () => resolve(image);
    image.onerror = () => reject(new Error("Could not read that image."));
    image.src = dataUrl;
  });
}

function sourceSize(source: ImageBitmap | HTMLImageElement): { width: number; height: number } {
  return source instanceof HTMLImageElement
    ? { width: source.naturalWidth, height: source.naturalHeight }
    : { width: source.width, height: source.height };
}

function centerCropToSquare(
  source: ImageBitmap | HTMLImageElement,
  size: number,
): HTMLCanvasElement {
  const canvas = document.createElement("canvas");
  canvas.width = size;
  canvas.height = size;
  const ctx = canvas.getContext("2d");
  if (!ctx) throw new Error("Canvas is not available in this browser.");
  const { width, height } = sourceSize(source);
  const side = Math.min(width, height);
  const sx = (width - side) / 2;
  const sy = (height - side) / 2;
  ctx.drawImage(source, sx, sy, side, side, 0, 0, size, size);
  return canvas;
}

function canvasToWebpBlob(canvas: HTMLCanvasElement, quality: number): Promise<Blob> {
  return new Promise((resolve, reject) => {
    canvas.toBlob(
      (blob) => {
        if (!blob) reject(new Error("WebP encoding failed."));
        else resolve(blob);
      },
      "image/webp",
      quality,
    );
  });
}

function blobToBase64(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => {
      const result = reader.result;
      if (typeof result !== "string") {
        reject(new Error("Could not encode avatar."));
        return;
      }
      const comma = result.indexOf(",");
      resolve(comma >= 0 ? result.slice(comma + 1) : result);
    };
    reader.onerror = () => reject(new Error("Could not encode avatar."));
    reader.readAsDataURL(blob);
  });
}

/**
 * Center-crop → 128×128 → WebP (~0.7 quality), re-encoding down until ≤12 KB.
 * Returns base64 WebP without a data-URL prefix.
 */
export async function encodeAvatarFromFile(file: File): Promise<string> {
  if (file.size > 20 * 1024 * 1024) {
    throw new Error("Choose a photo smaller than 20 MB.");
  }
  const source = await loadImageSource(file);
  const canvas = centerCropToSquare(source, AVATAR_SIZE);
  if (source instanceof ImageBitmap) source.close();
  let quality = INITIAL_QUALITY;
  let blob = await canvasToWebpBlob(canvas, quality);
  while (blob.size > AVATAR_MAX_BYTES && quality > MIN_QUALITY) {
    quality = Math.max(MIN_QUALITY, quality - QUALITY_STEP);
    blob = await canvasToWebpBlob(canvas, quality);
  }
  if (blob.size > AVATAR_MAX_BYTES) {
    throw new Error("That photo is still too large after compression. Try a simpler image.");
  }
  return blobToBase64(blob);
}
