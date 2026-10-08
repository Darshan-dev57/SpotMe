import { describe, expect, it } from "vitest";
import type { Match } from "@/lib/api";
import { groupPhotos, splitPhotos } from "@/lib/removal";

const photo = (id: string, solo: boolean): Match => ({ id, thumbUrl: "", downloadUrl: "", faceId: 1, solo });
const photos = [photo("a", true), photo("b", false), photo("c", false)];

describe("splitPhotos", () => {
  it("counts solo and group photos", () => {
    expect(splitPhotos(photos)).toEqual({ solo: 1, group: 2 });
    expect(splitPhotos([])).toEqual({ solo: 0, group: 0 });
  });
});

describe("groupPhotos", () => {
  it("keeps group photos when solo photos are removed", () => {
    expect(groupPhotos(photos).map((p) => p.id)).toEqual(["b", "c"]);
  });

  it("keeps nothing when every photo is solo", () => {
    expect(groupPhotos([photo("a", true)])).toEqual([]);
  });
});
