export function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, { day: "numeric", month: "short", year: "numeric" });
}

export function daysLeft(iso: string): number {
  return Math.max(0, Math.ceil((new Date(iso).getTime() - Date.now()) / 86_400_000));
}

export function plural(n: number, one: string, many = one + "s"): string {
  return `${n} ${n === 1 ? one : many}`;
}

/** Turns the API's error codes for a photo into words an organizer understands. */
export function photoError(code: string | null): string {
  switch (code) {
    case "corrupt_image":
      return "File is damaged";
    case "unsupported_type":
      return "Not a supported photo type";
    case "too_many_pixels":
      return "Too many pixels";
    case "file_missing":
      return "File went missing";
    default:
      return "Couldn't process";
  }
}

/** Words for a Drive import error code (the whole import, or one file in it). */
export function driveError(code: string | null): string {
  switch (code) {
    case "folder_not_public":
      return "This folder isn't public. In Drive, share it as \"Anyone with the link\" and try again.";
    case "event_closed":
      return "This event is expired or being deleted.";
    case "listing_failed":
      return "Google didn't answer while reading the folder. Try again in a few minutes.";
    case "download_failed":
      return "Couldn't download it from Drive";
    case "event_full":
      return "The event is full";
    case "removed_photo":
      return "Removed on a guest's request";
    default:
      return photoError(code);
  }
}

/** How far an import has got, 0 to 100. */
export function driveProgress(i: { found: number; imported: number; duplicates: number; failed: number }): number {
  if (i.found === 0) return 0;
  return Math.round(((i.imported + i.duplicates + i.failed) / i.found) * 100);
}
