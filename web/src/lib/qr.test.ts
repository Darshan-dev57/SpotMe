import QRCode from "qrcode";
import jsQR from "jsqr";
import { describe, expect, it } from "vitest";
import { qrFileName } from "./qr";

/** Draws the QR modules into RGBA pixels (4 px per module, 4-module quiet zone), like a PNG would show. */
function render(text: string) {
  const qr = QRCode.create(text, { errorCorrectionLevel: "M" });
  const scale = 4;
  const margin = 4;
  const size = (qr.modules.size + margin * 2) * scale;
  const pixels = new Uint8ClampedArray(size * size * 4).fill(255);
  for (let y = 0; y < qr.modules.size; y++) {
    for (let x = 0; x < qr.modules.size; x++) {
      if (!qr.modules.get(y, x)) continue;
      for (let dy = 0; dy < scale; dy++) {
        for (let dx = 0; dx < scale; dx++) {
          const px = ((y + margin) * scale + dy) * size + (x + margin) * scale + dx;
          pixels.fill(0, px * 4, px * 4 + 3);
        }
      }
    }
  }
  return { pixels, size };
}

describe("invite QR code", () => {
  it("scans back to the exact invite link", () => {
    const link = "https://spotme.sites.tab.bot/join#lERe7_YJhKgNyCx28OaPRXe8BccZsO8kn3rUo0Y9spU";
    const { pixels, size } = render(link);
    expect(jsQR(pixels, size, size)?.data).toBe(link);
  });

  it("names the download after the guest", () => {
    expect(qrFileName("Asha Rao")).toBe("asha-rao-invite-qr.png");
    expect(qrFileName("  ")).toBe("guest-invite-qr.png");
  });
});
