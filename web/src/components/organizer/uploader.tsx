"use client";

import { useRef, useState } from "react";
import { ImagePlus } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Progress } from "@/components/ui/progress";
import { ApiError, api } from "@/lib/api";
import { cn } from "@/lib/utils";

type Item = { name: string; state: "waiting" | "uploading" | "done" | "duplicate" | "error"; message?: string };

const PARALLEL = 4; // a few at a time: fast, without flooding the server

/**
 * Sends each photo as its own request, four at a time, so one bad file never fails the batch
 * and a dropped connection only costs the photos still in flight.
 */
export function Uploader({ eventId, token, onUploaded }: { eventId: string; token: string; onUploaded: () => void }) {
  const input = useRef<HTMLInputElement>(null);
  const [items, setItems] = useState<Item[]>([]);
  const [dragging, setDragging] = useState(false);
  const busy = items.some((i) => i.state === "waiting" || i.state === "uploading");

  function update(index: number, patch: Partial<Item>) {
    setItems((prev) => prev.map((it, i) => (i === index ? { ...it, ...patch } : it)));
  }

  async function start(files: File[]) {
    const images = files.filter((f) => /image\/(jpeg|png)/.test(f.type) || /\.(jpe?g|png)$/i.test(f.name));
    if (images.length === 0) return;
    const offset = items.length;
    setItems((prev) => [...prev, ...images.map((f) => ({ name: f.name, state: "waiting" as const }))]);

    let next = 0;
    async function worker() {
      while (next < images.length) {
        const i = next++;
        update(offset + i, { state: "uploading" });
        const form = new FormData();
        form.append("file", images[i]);
        try {
          const res = await api<{ status: string }>(`/api/events/${eventId}/photos`, { token, body: form });
          update(offset + i, { state: res.status === "duplicate" ? "duplicate" : "done" });
        } catch (e) {
          update(offset + i, { state: "error", message: e instanceof ApiError ? e.message : "Upload failed" });
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
          start(Array.from(e.dataTransfer.files));
        }}
        className={cn(
          "flex flex-col items-center gap-3 rounded-xl border-2 border-dashed px-6 py-8 text-center transition-colors",
          dragging ? "border-primary bg-accent" : "border-input",
        )}
      >
        <ImagePlus className="size-7 text-muted-foreground" aria-hidden="true" />
        <div>
          <p className="font-medium">Drop photos here</p>
          <p className="mt-1 text-sm text-muted-foreground">JPEG or PNG, up to 20 MB each.</p>
        </div>
        <Button type="button" variant="outline" onClick={() => input.current?.click()}>
          Choose photos
        </Button>
        <input
          ref={input}
          type="file"
          accept="image/jpeg,image/png"
          multiple
          hidden
          onChange={(e) => {
            start(Array.from(e.target.files ?? []));
            e.target.value = "";
          }}
        />
      </div>

      {items.length > 0 && (
        <div className="mt-4 rounded-lg bg-muted px-4 py-3" aria-live="polite">
          <div className="flex justify-between text-sm">
            <span>{busy ? "Uploading" : "Upload finished"}</span>
            <span className="tabular text-muted-foreground">
              {finished}/{items.length}
            </span>
          </div>
          <Progress value={(finished / items.length) * 100} className="mt-2 h-1.5" />
          {(errors.length > 0 || duplicates > 0) && (
            <ul className="mt-3 space-y-1 text-sm">
              {duplicates > 0 && <li className="text-muted-foreground">{duplicates} already in this event, skipped.</li>}
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
