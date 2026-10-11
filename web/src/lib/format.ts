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
