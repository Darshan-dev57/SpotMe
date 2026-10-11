import QRCode from "qrcode";

/**
 * A QR code for an invite link as a PNG data URL, made in the browser so the link never goes to
 * another server. The margin is the "quiet zone" scanners need around the code.
 */
export function qrPng(url: string, width: number): Promise<string> {
  return QRCode.toDataURL(url, { width, margin: 2, errorCorrectionLevel: "M" });
}

/** "Asha Rao" -> "asha-rao-invite-qr.png" */
export function qrFileName(label: string): string {
  const slug = label
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-|-$/g, "");
  return `${slug || "guest"}-invite-qr.png`;
}
