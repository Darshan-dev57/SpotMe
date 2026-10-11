/**
 * How the selfie step opens the camera:
 * - "capture-input": phones and tablets. <input capture="user"> opens the front camera app.
 * - "live": laptops with a webcam. A preview in the page and a "Take photo" button.
 * - "file-only": no camera API (old browser, or a page not served over HTTPS).
 */
export type CameraMode = "capture-input" | "live" | "file-only";

export function cameraMode(env: { coarsePointer: boolean; hasGetUserMedia: boolean }): CameraMode {
  if (env.coarsePointer) return "capture-input";
  return env.hasGetUserMedia ? "live" : "file-only";
}

export function detectCameraMode(): CameraMode {
  if (typeof window === "undefined") return "file-only";
  return cameraMode({
    coarsePointer: window.matchMedia?.("(pointer: coarse)").matches ?? false,
    hasGetUserMedia: !!navigator.mediaDevices?.getUserMedia,
  });
}

/** Turns the camera off (the light goes out). Safe to call twice or with no stream. */
export function stopStream(stream: MediaStream | null | undefined) {
  stream?.getTracks().forEach((track) => track.stop());
}

/** What to tell the guest when the camera can't be opened. */
export function cameraError(e: unknown): string {
  const name = e instanceof Error ? e.name : "";
  if (name === "NotAllowedError" || name === "SecurityError") {
    return "Camera access is blocked. Allow it in your browser, or choose a photo instead.";
  }
  if (name === "NotFoundError" || name === "OverconstrainedError") {
    return "No camera found. Choose a photo instead.";
  }
  if (name === "NotReadableError") {
    return "The camera is busy in another app. Close it, or choose a photo instead.";
  }
  return "The camera could not be opened. Choose a photo instead.";
}
