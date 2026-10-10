import { describe, expect, it } from "vitest";
import { isPhotoFile, sniffNeedsConversion } from "./image";

function ftyp(...brands: string[]): Uint8Array {
  // size, "ftyp", major brand, minor version, then the compatible brands
  const size = 16 + 4 * (brands.length - 1);
  const bytes = new Uint8Array(64);
  bytes.set([0, 0, 0, size]);
  const put = (at: number, s: string) => bytes.set([...s].map((c) => c.charCodeAt(0)), at);
  put(4, "ftyp");
  put(8, brands[0]);
  brands.slice(1).forEach((b, i) => put(16 + 4 * i, b));
  return bytes;
}

describe("isPhotoFile", () => {
  it("takes every supported photo type by name or type", () => {
    for (const name of ["a.jpg", "b.JPEG", "c.png", "d.webp", "e.tif", "f.tiff", "g.bmp", "h.gif", "IMG_1.HEIC", "i.heif", "j.avif"]) {
      expect(isPhotoFile({ name, type: "" })).toBe(true);
    }
    expect(isPhotoFile({ name: "photo", type: "image/webp" })).toBe(true);
  });

  it("skips other files and hidden system files", () => {
    for (const name of ["notes.txt", "clip.mp4", "raw.NEF", ".DS_Store", "._IMG_0001.jpg", "Thumbs.db"]) {
      expect(isPhotoFile({ name, type: "" })).toBe(false);
    }
  });
});

describe("sniffNeedsConversion", () => {
  it("finds HEIC and AVIF from the ftyp brands", () => {
    expect(sniffNeedsConversion(ftyp("heic", "mif1", "heic"))).toBe("heic");
    expect(sniffNeedsConversion(ftyp("mif1", "mif1", "avif"))).toBe("avif");
  });

  it("leaves JPEG, PNG and MP4 alone", () => {
    expect(sniffNeedsConversion(new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 0, 0, 0, 0, 0, 0, 0, 0]))).toBeNull();
    expect(sniffNeedsConversion(ftyp("isom", "isom", "mp42"))).toBeNull();
    expect(sniffNeedsConversion(new Uint8Array(4))).toBeNull();
  });
});
