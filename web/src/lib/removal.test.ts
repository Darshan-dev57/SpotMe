import { describe, expect, it } from "vitest";
import type { Match } from "@/lib/api";
import { photosLeft, splitPhotos } from "@/lib/removal";

const photo = (id: string, solo: boolean): Match => ({ id, thumbUrl: "", downloadUrl: "", faceId: 1, solo });
const photos = [photo("a", true), photo("b", false), photo("c", false)];

describe("splitPhotos", () => {
  it("counts solo and group photos", () => {
    expect(splitPhotos(photos)).toEqual({ solo: 1, group: 2 });
    expect(splitPhotos([])).toEqual({ solo: 0, group: 0 });
  });
});

describe("photosLeft", () => {
  it("keeps group photos when only solo photos are removed", () => {
    expect(photosLeft(photos, "solo").map((p) => p.id)).toEqual(["b", "c"]);
  });

  it("keeps nothing when the guest is erased everywhere", () => {
    expect(photosLeft(photos, "erase")).toEqual([]);
  });
});
