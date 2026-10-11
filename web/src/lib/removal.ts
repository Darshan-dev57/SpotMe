import type { Match } from "@/lib/api";

/** How many of the guest's photos show only them, and how many are group photos. */
export function splitPhotos(photos: Match[]) {
  const solo = photos.filter((p) => p.solo).length;
  return { solo, group: photos.length - solo };
}

/** The photos that stay in the guest's results after a removal request: the group photos. */
export function groupPhotos(photos: Match[]): Match[] {
  return photos.filter((p) => !p.solo);
}
