"use client";

import { useRef, useState } from "react";
import { FolderUp, ImagePlus } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Progress } from "@/components/ui/progress";
import { ApiError, api } from "@/lib/api";
import { type Entry, filesFromEntries, splitPhotos } from "@/lib/folder";
import { ConversionError, PHOTO_ACCEPT, prepareUpload, smallerCopy } from "@/lib/image";
import { cn } from "@/lib/utils";

// lets the folder picker attribute through React's typed props
const folderPicker = { webkitdirectory: "", directory: "" } as Record<string, string>;

type Item = { name: string; state: "waiting" | "uploading" | "done" | "duplicate" | "error"; message?: string };

const PARALLEL = 4; // a few at a time: fast, without flooding the server

/**
 * Sends each photo as its own request, four at a time, so one bad file never fails the batch
 * and a dropped connection only costs the photos still in flight.
 */
export function Uploader({ eventId, token, onUploaded }: { eventId: string; token: string; onUploaded: () => void }) {
  const input = useRef<HTMLInputElement>(null);
  const folderInput = useRef<HTMLInputElement>(null);
  const [items, setItems] = useState<Item[]>([]);
  const [skipped, setSkipped] = useState(0);
  const added = useRef(0); // photos added so far; a ref so two quick drops never share list positions
  const [dragging, setDragging] = useState(false);
  const busy = items.some((i) => i.state === "waiting" || i.state === "uploading");

  function update(index: number, patch: Partial<Item>) {
    setItems((prev) => prev.map((it, i) => (i === index ? { ...it, ...patch } : it)));
  }

  /** One photo: HEIC/AVIF become JPEG first; a photo over the server's pixel cap is sent again as a smaller copy. */
  async function send(file: File): Promise<string> {
    const form = (body: Blob) => {
      const f = new FormData();
      f.append("file", body, file.name);
      return f;
    };
    const body = await prepareUpload(file);
    try {
      return (await api<{ status: string }>(`/api/events/${eventId}/photos`, { token, body: form(body) })).status;
    } catch (e) {
      const smaller = e instanceof ApiError && e.code === "too_many_pixels" ? await smallerCopy(body) : null;
      if (!smaller) throw e;
      return (await api<{ status: string }>(`/api/events/${eventId}/photos`, { token, body: form(smaller) })).status;
    }
  }

  async function start(files: File[]) {
    const { photos: images, skipped: notPhotos } = splitPhotos(files);
    setSkipped((n) => n + notPhotos);
    if (images.length === 0) return;
    const offset = added.current;
    added.current += images.length;
    // inside a folder, show "day 2/IMG_0001.jpg" so a failed file can be found again
    const label = (f: File) => f.webkitRelativePath || f.name;
    setItems((prev) => [...prev, ...images.map((f) => ({ name: label(f), state: "waiting" as const }))]);

    let next = 0;
    let full: string | null = null; // once the event is full, the rest of a big folder is not sent
    async function worker() {
      while (next < images.length) {
        const i = next++;
        if (full) {
          update(offset + i, { state: "error", message: full });
          continue;
        }
        update(offset + i, { state: "uploading" });
        try {
          const status = await send(images[i]);
          update(offset + i, { state: status === "duplicate" ? "duplicate" : "done" });
        } catch (e) {
          const message = e instanceof ApiError || e instanceof ConversionError ? e.message : "Upload failed";
          if (e instanceof ApiError && e.code === "event_full") full = message;
          update(offset + i, { state: "error", message });
        }
        if (i % 8 === 0) onUploaded();
      }
    }
    await Promise.all(Array.from({ length: Math.min(PARALLEL, images.length) }, worker));
    onUploaded();
  }

  const finished = items.filter((i) => i.state !== "waiting" && i.state !== "uploading").length;
  const errors = items.filter((i) => i.state === "error");
  const duplicates = items.filter((i) => i.state === "duplicate").length;

  return (
    <div>
      <div
        onDragOver={(e) => {
          e.preventDefault();
          setDragging(true);
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={(e) => {
          e.preventDefault();
          setDragging(false);
          // entries must be taken while the drop event is running; folders are read after
          const entries = Array.from(e.dataTransfer.items)
            .map((item) => item.webkitGetAsEntry?.() as Entry | null)
            .filter((entry): entry is Entry => entry !== null);
          if (entries.length > 0) {
            filesFromEntries(entries).then(start);
          } else {
            start(Array.from(e.dataTransfer.files));
          }
        }}
        className={cn(
          "flex flex-col items-center gap-3 rounded-xl border-2 border-dashed px-6 py-8 text-center transition-colors",
          dragging ? "border-primary bg-accent" : "border-input",
        )}
      >
        <ImagePlus className="size-7 text-muted-foreground" aria-hidden="true" />
        <div>
          <p className="font-medium">Drop photos or a folder here</p>
          <p className="mt-1 text-sm text-muted-foreground">JPEG, PNG, HEIC, WebP, AVIF, TIFF, BMP or GIF, up to 20 MB each.</p>
        </div>
        <div className="flex flex-wrap justify-center gap-2">
          <Button type="button" variant="outline" onClick={() => input.current?.click()}>
            <ImagePlus /> Choose photos
          </Button>
          <Button type="button" variant="outline" onClick={() => folderInput.current?.click()}>
            <FolderUp /> Choose a folder
          </Button>
        </div>
        <input
          ref={input}
          type="file"
          accept={PHOTO_ACCEPT}
          multiple
          hidden
          onChange={(e) => {
            start(Array.from(e.target.files ?? []));
            e.target.value = "";
          }}
        />
        <input
          ref={folderInput}
          type="file"
          {...folderPicker}
          multiple
          hidden
          onChange={(e) => {
            start(Array.from(e.target.files ?? []));
            e.target.value = "";
          }}
        />
      </div>

      {items.length === 0 && skipped > 0 && (
        <p className="mt-4 text-sm text-muted-foreground" aria-live="polite">
          No photos found. {skipped} other {skipped === 1 ? "file was" : "files were"} skipped.
        </p>
      )}

      {items.length > 0 && (
        <div className="mt-4 rounded-lg bg-muted px-4 py-3" aria-live="polite">
          <div className="flex justify-between text-sm">
            <span>{busy ? "Uploading" : "Upload finished"}</span>
            <span className="tabular text-muted-foreground">
              {finished}/{items.length}
            </span>
          </div>
          <Progress value={(finished / items.length) * 100} className="mt-2 h-1.5" />
          {(errors.length > 0 || duplicates > 0 || skipped > 0) && (
            <ul className="mt-3 space-y-1 text-sm">
              {duplicates > 0 && <li className="text-muted-foreground">{duplicates} already in this event, skipped.</li>}
              {skipped > 0 && (
                <li className="text-muted-foreground">
                  {skipped} {skipped === 1 ? "file is" : "files are"} not a photo (videos, documents), skipped.
                </li>
              )}
              {errors.slice(0, 5).map((e, i) => (
                <li key={i} className="text-destructive">
                  {e.name}: {e.message}
                </li>
              ))}
              {errors.length > 5 && <li className="text-destructive">and {errors.length - 5} more.</li>}
            </ul>
          )}
        </div>
      )}
    </div>
  );
}
