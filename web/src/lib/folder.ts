/**
 * Reading a dropped folder. The browser gives a tree of entries: a directory is read with
 * readEntries(), which returns at most about 100 entries per call, so it is called until it
 * returns an empty list.
 */

import { isPhotoFile } from "./image";

/** The parts of the browser's FileSystemEntry API used here (kept small so tests can fake it). */
export type Entry = {
  isFile: boolean;
  isDirectory: boolean;
  name: string;
  fullPath?: string;
  file?: (ok: (file: File) => void, fail: (err: unknown) => void) => void;
  createReader?: () => { readEntries: (ok: (entries: Entry[]) => void, fail: (err: unknown) => void) => void };
};

/** Every file under the given entries, subfolders included, in folder order. */
export async function filesFromEntries(entries: Entry[]): Promise<File[]> {
  const files: File[] = [];
  async function walk(entry: Entry) {
    if (entry.isFile && entry.file) {
      files.push(await new Promise<File>((ok, fail) => entry.file!(ok, fail)));
    } else if (entry.isDirectory && entry.createReader) {
      const reader = entry.createReader();
      for (;;) {
        const batch = await new Promise<Entry[]>((ok, fail) => reader.readEntries(ok, fail));
        if (batch.length === 0) break;
        for (const child of batch) await walk(child);
      }
    }
  }
  for (const entry of entries) await walk(entry);
  return files;
}

/** Photos to upload, and how many other files were left out (videos, documents, hidden system files). */
export function splitPhotos(files: File[]): { photos: File[]; skipped: number } {
  const photos = files.filter(isPhotoFile);
  return { photos, skipped: files.length - photos.length };
}
