import { describe, expect, it } from "vitest";
import { type Entry, filesFromEntries, splitPhotos } from "./folder";

function file(name: string): Entry {
  return { isFile: true, isDirectory: false, name, file: (ok) => ok(new File(["x"], name)) };
}

/** A folder whose reader hands out its children in batches, like Chrome does (100 at a time). */
function folder(name: string, children: Entry[], batch = 2): Entry {
  return {
    isFile: false,
    isDirectory: true,
    name,
    createReader: () => {
      let at = 0;
      return {
        readEntries: (ok) => {
          ok(children.slice(at, at + batch));
          at += batch;
        },
      };
    },
  };
}

describe("filesFromEntries", () => {
  it("walks subfolders and reads every batch", async () => {
    const tree = folder("wedding", [
      file("a.jpg"),
      file("b.heic"),
      file("notes.txt"),
      folder("day 2", [file("c.png"), folder("raw", [file("d.webp")]), file("e.mov")]),
    ]);
    const names = (await filesFromEntries([tree, file("loose.gif")])).map((f) => f.name);
    expect(names).toEqual(["a.jpg", "b.heic", "notes.txt", "c.png", "d.webp", "e.mov", "loose.gif"]);
  });

  it("returns nothing for an empty folder", async () => {
    expect(await filesFromEntries([folder("empty", [])])).toEqual([]);
  });
});

describe("splitPhotos", () => {
  it("keeps photos and counts the rest", () => {
    const files = ["a.jpg", "b.HEIC", "clip.mp4", ".DS_Store", "c.tiff", "readme.pdf"].map((n) => new File(["x"], n));
    const { photos, skipped } = splitPhotos(files);
    expect(photos.map((f) => f.name)).toEqual(["a.jpg", "b.HEIC", "c.tiff"]);
    expect(skipped).toBe(3);
  });
});
