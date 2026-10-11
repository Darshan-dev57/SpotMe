import { GuestPhoto } from "@/lib/api";

/** Splits a list into pieces of at most `size` items (the server caps ids per request). */
export function chunk<T>(items: T[], size: number): T[][] {
  const out: T[][] = [];
  for (let i = 0; i < items.length; i += size) out.push(items.slice(i, i + size));
  return out;
}

/** Shared event photos minus the ones already in the guest's own matches, so none shows twice. */
export function withoutMatches(photos: GuestPhoto[], matchedIds: string[]): GuestPhoto[] {
  const matched = new Set(matchedIds);
  return photos.filter((p) => !matched.has(p.id));
}
