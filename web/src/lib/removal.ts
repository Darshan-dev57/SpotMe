import type { Match, RemovalMode } from "@/lib/api";

/** How many of the guest's photos show only them, and how many are group photos. */
export function splitPhotos(photos: Match[]) {
  const solo = photos.filter((p) => p.solo).length;
  return { solo, group: photos.length - solo };
}

/** The photos that leave the guest's results once a request with this mode is sent. */
export function photosLeft(photos: Match[], mode: RemovalMode): Match[] {
  return mode === "erase" ? [] : photos.filter((p) => !p.solo);
}
