import { describe, expect, it, vi } from "vitest";
import { cameraError, cameraMode, stopStream } from "./camera";

describe("cameraMode", () => {
  it("uses the phone camera app on touch screens", () => {
    expect(cameraMode({ coarsePointer: true, hasGetUserMedia: true })).toBe("capture-input");
  });

  it("shows a live preview on laptops", () => {
    expect(cameraMode({ coarsePointer: false, hasGetUserMedia: true })).toBe("live");
  });

  it("falls back to choosing a file without a camera API", () => {
    expect(cameraMode({ coarsePointer: false, hasGetUserMedia: false })).toBe("file-only");
  });
});

describe("stopStream", () => {
  it("stops every track", () => {
    const tracks = [{ stop: vi.fn() }, { stop: vi.fn() }];
    stopStream({ getTracks: () => tracks } as unknown as MediaStream);
    tracks.forEach((t) => expect(t.stop).toHaveBeenCalledOnce());
  });

  it("ignores a missing stream", () => {
    expect(() => stopStream(null)).not.toThrow();
  });
});

describe("cameraError", () => {
  it("explains a blocked camera", () => {
    const denied = Object.assign(new Error("denied"), { name: "NotAllowedError" });
    expect(cameraError(denied)).toMatch(/blocked/);
  });

  it("explains a missing camera", () => {
    const missing = Object.assign(new Error("none"), { name: "NotFoundError" });
    expect(cameraError(missing)).toMatch(/No camera/);
  });
});
