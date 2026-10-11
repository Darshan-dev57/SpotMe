import { describe, expect, it } from "vitest";
import { chunk, withoutMatches } from "./photos";

const photo = (id: string) => ({ id, thumbUrl: "", downloadUrl: "" });

describe("chunk", () => {
  it("splits into pieces of the given size", () => {
    expect(chunk([1, 2, 3, 4, 5], 2)).toEqual([[1, 2], [3, 4], [5]]);
  });

  it("returns nothing for an empty list", () => {
    expect(chunk([], 2000)).toEqual([]);
  });
});

describe("withoutMatches", () => {
  it("drops shared photos the guest already has in their matches", () => {
    const shared = [photo("a"), photo("b"), photo("c")];
    expect(withoutMatches(shared, ["b"]).map((p) => p.id)).toEqual(["a", "c"]);
  });
});
