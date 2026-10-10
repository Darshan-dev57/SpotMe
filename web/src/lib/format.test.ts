import { afterEach, describe, expect, it, vi } from "vitest";
import { daysLeft, driveError, driveProgress, photoError, plural } from "./format";

describe("plural", () => {
  it("uses the singular only for exactly one", () => {
    expect(plural(1, "photo")).toBe("1 photo");
    expect(plural(0, "photo")).toBe("0 photos");
    expect(plural(12, "photo")).toBe("12 photos");
  });

  it("takes an irregular plural", () => {
    expect(plural(2, "person", "people")).toBe("2 people");
  });
});

describe("daysLeft", () => {
  afterEach(() => vi.useRealTimers());

  it("rounds a part day up and never goes below zero", () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-10-10T12:00:00Z"));
    expect(daysLeft("2026-10-11T00:00:00Z")).toBe(1);
    expect(daysLeft("2026-10-20T12:00:00Z")).toBe(10);
    expect(daysLeft("2026-10-01T00:00:00Z")).toBe(0);
  });
});

describe("photoError", () => {
  it("turns API codes into plain words", () => {
    expect(photoError("corrupt_image")).toBe("File is damaged");
    expect(photoError("unsupported_type")).toBe("Not a supported photo type");
  });

  it("has a fallback for codes it does not know", () => {
    expect(photoError("something_new")).toBe("Couldn't process");
    expect(photoError(null)).toBe("Couldn't process");
  });
});

describe("driveError", () => {
  it("tells the organizer how to fix a private folder", () => {
    expect(driveError("folder_not_public")).toContain("Anyone with the link");
  });

  it("falls back to the photo errors", () => {
    expect(driveError("too_many_pixels")).toBe("Too many pixels");
  });
});

describe("driveProgress", () => {
  it("counts imported, duplicate and failed files as done", () => {
    expect(driveProgress({ found: 10, imported: 5, duplicates: 2, failed: 1 })).toBe(80);
    expect(driveProgress({ found: 0, imported: 0, duplicates: 0, failed: 0 })).toBe(0);
  });
});
