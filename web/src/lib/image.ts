/** File types the organizer page sends. HEIC/HEIF and AVIF are turned into JPEG here first. */
export const PHOTO_EXTENSIONS = /\.(jpe?g|png|webp|tiff?|bmp|gif|heic|heif|avif)$/i;
export const PHOTO_ACCEPT = "image/*,.heic,.heif,.avif";

/** True for a file name the uploader should send; hidden files like ".DS_Store" or "._IMG.jpg" are skipped. */
export function isPhotoFile(file: { name: string; type: string }): boolean {
  if (file.name.startsWith(".")) return false;
  return PHOTO_EXTENSIONS.test(file.name) || /^image\/(jpeg|png|webp|tiff|bmp|gif|heic|heif|avif)$/.test(file.type);
}

/**
 * Reads the type from the first bytes, the same way the server does: HEIC/HEIF and AVIF
 * are ISO media files with an "ftyp" box and a brand. Everything else is left to the server.
 */
export function sniffNeedsConversion(head: Uint8Array): "heic" | "avif" | null {
  if (head.length < 12) return null;
  const text = (from: number) => String.fromCharCode(...head.slice(from, from + 4));
  if (text(4) !== "ftyp") return null;
  const boxEnd = Math.min(head.length, (head[0] << 24) | (head[1] << 16) | (head[2] << 8) | head[3]);
  let heic = false;
  for (let i = 8; i + 4 <= boxEnd; i += 4) {
    const brand = text(i);
    if (brand === "avif" || brand === "avis") return "avif";
    if (["heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1"].includes(brand)) heic = true;
  }
  return heic ? "heic" : null;
}

/** Thrown when this browser can't read a photo that has to be converted before upload. */
export class ConversionError extends Error {}

/** Draws a decoded picture onto a canvas, at most `maxSide` pixels on the longest side, and saves a JPEG. */
async function canvasJpeg(source: ImageBitmap, maxSide: number): Promise<Blob> {
  const scale = Math.min(1, maxSide / Math.max(source.width, source.height));
  const canvas = document.createElement("canvas");
  canvas.width = Math.round(source.width * scale);
  canvas.height = Math.round(source.height * scale);
  canvas.getContext("2d")!.drawImage(source, 0, 0, canvas.width, canvas.height);
  source.close();
  return new Promise<Blob>((resolve, reject) =>
    canvas.toBlob((b) => (b ? resolve(b) : reject(new Error("encode failed"))), "image/jpeg", 0.92),
  );
}

/**
 * Makes a JPEG of a photo the server can't read (HEIC/HEIF, AVIF).
 * First the browser's own decoder (Safari reads HEIC, most browsers read AVIF); for HEIC on
 * other browsers a small decoder is loaded only when it is needed.
 */
export async function convertToJpeg(file: Blob, kind: "heic" | "avif", maxSide = 4000): Promise<Blob> {
  try {
    return await canvasJpeg(await createImageBitmap(file), maxSide);
  } catch {
    // fall through to the decoder below
  }
  if (kind === "heic") {
    try {
      const { heicTo } = await import("heic-to");
      const bitmap = await heicTo({ blob: file, type: "bitmap" });
      return await canvasJpeg(bitmap, maxSide);
    } catch {
      // fall through to the error
    }
  }
  throw new ConversionError(
    kind === "heic"
      ? "This browser can't read HEIC photos. Export them as JPEG, or try Safari or Chrome."
      : "This browser can't read AVIF photos. Export them as JPEG, or use a newer browser.",
  );
}

/** What the organizer uploader actually sends for a file: the file itself, or a JPEG made from it. */
export async function prepareUpload(file: File): Promise<Blob> {
  const kind = sniffNeedsConversion(new Uint8Array(await file.slice(0, 64).arrayBuffer()));
  return kind ? convertToJpeg(file, kind) : file;
}

/** A smaller JPEG copy, for photos the server refuses as too many pixels. Null if the browser can't read it. */
export async function smallerCopy(file: Blob, maxSide = 4000): Promise<Blob | null> {
  try {
    return await canvasJpeg(await createImageBitmap(file), maxSide);
  } catch {
    return null;
  }
}

/**
 * Shrinks a phone photo (often 12 MP) to at most `maxSide` pixels before upload.
 * Faster on mobile data, and the face models only look at about this size anyway.
 */
export async function shrinkImage(file: File, maxSide = 1600): Promise<Blob> {
  try {
    const bitmap = await createImageBitmap(file); // applies EXIF rotation
    const scale = Math.min(1, maxSide / Math.max(bitmap.width, bitmap.height));
    if (scale === 1 && file.type === "image/jpeg") return file;
    const canvas = document.createElement("canvas");
    canvas.width = Math.round(bitmap.width * scale);
    canvas.height = Math.round(bitmap.height * scale);
    canvas.getContext("2d")!.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
    bitmap.close();
    return await new Promise<Blob>((resolve, reject) =>
      canvas.toBlob((b) => (b ? resolve(b) : reject(new Error("encode failed"))), "image/jpeg", 0.9),
    );
  } catch {
    // an iPhone HEIC selfie on a browser without its own HEIC decoder
    try {
      const kind = sniffNeedsConversion(new Uint8Array(await file.slice(0, 64).arrayBuffer()));
      return kind ? await convertToJpeg(file, kind, maxSide) : file;
    } catch {
      return file; // let the server decide
    }
  }
}
