"use client";

import { useState } from "react";
import { AlertTriangle, Check, EyeOff, Flag, Globe, Loader2, Lock, RotateCcw, Trash2 } from "lucide-react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Empty, EmptyDescription, EmptyHeader, EmptyTitle } from "@/components/ui/empty";
import { ApiError, Photo, api, mediaUrl } from "@/lib/api";
import { photoError } from "@/lib/format";
import { chunk } from "@/lib/photos";
import { cn } from "@/lib/utils";

type Filter = "all" | "ready" | "public" | "working" | "failed" | "hidden";

const FILTERS: { key: Filter; label: string }[] = [
  { key: "all", label: "All" },
  { key: "ready", label: "Ready" },
  { key: "public", label: "Public" },
  { key: "working", label: "In progress" },
  { key: "failed", label: "Failed" },
  { key: "hidden", label: "Hidden" },
];

function matches(p: Photo, f: Filter) {
  if (f === "ready") return p.status === "ready" && !p.hidden && !p.suppressed;
  if (f === "public") return p.public;
  if (f === "working") return p.status === "queued" || p.status === "processing";
  if (f === "failed") return p.status === "failed";
  if (f === "hidden") return p.hidden || p.suppressed;
  return true;
}

/** The organizer's review grid: what is indexed, what failed, what guests will see. */
export function PhotoGrid({
  photos,
  eventId,
  token,
  onChanged,
}: {
  photos: Photo[];
  eventId: string;
  token: string;
  onChanged: () => void;
}) {
  const [filter, setFilter] = useState<Filter>("all");
  const [selected, setSelected] = useState<string | null>(null);
  // bulk mode: tapping a photo picks it instead of opening its actions
  const [selecting, setSelecting] = useState(false);
  const [picked, setPicked] = useState<Set<string>>(new Set());
  const [saving, setSaving] = useState(false);
  const shown = photos.filter((p) => matches(p, filter));

  function pick(id: string) {
    setPicked((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  function stopSelecting() {
    setSelecting(false);
    setPicked(new Set());
  }

  async function setVisibility(visibility: "public" | "private") {
    const ids = [...picked];
    setSaving(true);
    try {
      let updated = 0;
      // the server takes up to 2,000 ids per request
      for (const part of chunk(ids, 2000)) {
        const res = await api<{ updated: number }>(`/api/events/${eventId}/photos/visibility`, {
          token,
          json: { photoIds: part, visibility },
        });
        updated += res.updated;
      }
      toast.success(
        visibility === "public"
          ? `${updated} ${updated === 1 ? "photo is" : "photos are"} now shown to every guest.`
          : `${updated} ${updated === 1 ? "photo is" : "photos are"} private again.`,
      );
      stopSelecting();
      onChanged();
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : "Could not change the photos.");
    }
    setSaving(false);
  }

  async function act(photo: Photo, action: "hide" | "unhide" | "retry" | "delete") {
    try {
      if (action === "delete") {
        await api(`/api/events/${eventId}/photos/${photo.id}`, { method: "DELETE", token });
        toast.success("Photo deleted, with its face data.");
      } else {
        await api(`/api/events/${eventId}/photos/${photo.id}/${action}`, { method: "POST", token });
      }
      setSelected(null);
      onChanged();
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : "That didn't work.");
    }
  }

  if (photos.length === 0) {
    return (
      <Empty className="mt-6 border">
        <EmptyHeader>
          <EmptyTitle>No photos yet</EmptyTitle>
          <EmptyDescription>Uploaded photos appear here while their faces are found.</EmptyDescription>
        </EmptyHeader>
      </Empty>
    );
  }

  return (
    <div className="mt-8">
      <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
        <p className="text-sm text-muted-foreground">
          Photos are private: a guest only sees the ones they are in. Make venue and crowd shots public to show them to
          everyone.
        </p>
        {!selecting && (
          <Button variant="outline" size="sm" onClick={() => setSelecting(true)}>
            <Check /> Select
          </Button>
        )}
      </div>
      {selecting && (
        <div className="sticky top-0 z-20 mb-3 flex flex-wrap items-center gap-2 rounded-lg border bg-background/95 p-2 backdrop-blur">
          <span className="tabular px-1 text-sm font-medium">{picked.size} selected</span>
          <Button variant="ghost" size="sm" onClick={() => setPicked(new Set(shown.map((p) => p.id)))}>
            Select all {shown.length}
          </Button>
          <div className="ml-auto flex flex-wrap gap-2">
            <Button size="sm" onClick={() => setVisibility("public")} disabled={picked.size === 0 || saving}>
              <Globe /> Make public
            </Button>
            <Button size="sm" variant="outline" onClick={() => setVisibility("private")} disabled={picked.size === 0 || saving}>
              <Lock /> Make private
            </Button>
            <Button size="sm" variant="ghost" onClick={stopSelecting}>
              Done
            </Button>
          </div>
        </div>
      )}
      <div className="flex gap-1.5 overflow-x-auto pb-1" role="tablist" aria-label="Filter photos">
        {FILTERS.map((f) => {
          const n = photos.filter((p) => matches(p, f.key)).length;
          return (
            <button
              key={f.key}
              role="tab"
              aria-selected={filter === f.key}
              onClick={() => setFilter(f.key)}
              className={cn(
                "shrink-0 rounded-full border px-3 py-1 text-sm transition-colors",
                filter === f.key ? "border-foreground bg-foreground text-background" : "hover:bg-accent",
              )}
            >
              {f.label} <span className="tabular opacity-70">{n}</span>
            </button>
          );
        })}
      </div>

      <ul className="mt-4 grid grid-cols-3 gap-1.5 sm:grid-cols-4 sm:gap-2 lg:grid-cols-6">
        {shown.map((p) => (
          <li key={p.id} className="relative">
            <button
              type="button"
              onClick={() => (selecting ? pick(p.id) : setSelected(selected === p.id ? null : p.id))}
              className={cn(
                "group block aspect-square w-full overflow-hidden rounded-md bg-muted ring-offset-2 ring-offset-background",
                selecting && picked.has(p.id) && "ring-[3px] ring-primary",
              )}
              aria-label={`Photo, ${p.status}${p.public ? ", public" : ""}`}
              aria-pressed={selecting ? picked.has(p.id) : undefined}
            >
              {p.thumbUrl && (
                // eslint-disable-next-line @next/next/no-img-element
                <img
                  src={mediaUrl(p.thumbUrl)}
                  alt=""
                  loading="lazy"
                  className={cn(
                    "h-full w-full object-cover transition-opacity",
                    (p.hidden || p.suppressed) && "opacity-35",
                    p.status !== "ready" && "opacity-60",
                  )}
                />
              )}
              <PhotoBadge photo={p} />
              {p.public && (
                <span className="absolute top-1 right-1 flex items-center gap-1 rounded bg-primary px-1.5 py-0.5 text-[11px] text-primary-foreground">
                  <Globe className="size-3" /> Public
                </span>
              )}
              {selecting && picked.has(p.id) && (
                <span className="absolute right-1 bottom-1 flex size-6 items-center justify-center rounded-full bg-primary text-primary-foreground">
                  <Check className="size-4" />
                </span>
              )}
            </button>
            {!selecting && selected === p.id && (
              <div className="absolute inset-x-1 bottom-1 z-10 flex flex-wrap gap-1 rounded-md bg-foreground/90 p-1 backdrop-blur-sm">
                {p.status === "failed" && (
                  <Button size="sm" variant="secondary" onClick={() => act(p, "retry")}>
                    <RotateCcw /> Retry
                  </Button>
                )}
                {p.status === "ready" && (
                  <Button size="sm" variant="secondary" onClick={() => act(p, p.hidden ? "unhide" : "hide")}>
                    <EyeOff /> {p.hidden ? "Show" : "Hide"}
                  </Button>
                )}
                <Button size="sm" variant="destructive" onClick={() => act(p, "delete")} aria-label="Delete photo">
                  <Trash2 />
                </Button>
              </div>
            )}
          </li>
        ))}
      </ul>
    </div>
  );
}

function PhotoBadge({ photo }: { photo: Photo }) {
  const base = "absolute top-1 left-1 flex items-center gap-1 rounded bg-foreground/80 px-1.5 py-0.5 text-[11px] text-background";
  if (photo.status === "queued" || photo.status === "processing") {
    return (
      <span className={base}>
        <Loader2 className="size-3 animate-spin" /> {photo.status === "queued" ? "Queued" : "Finding faces"}
      </span>
    );
  }
  if (photo.status === "failed") {
    return (
      <span className={cn(base, "bg-destructive")}>
        <AlertTriangle className="size-3" /> {photoError(photo.errorCode)}
      </span>
    );
  }
  if (photo.suppressed) return <span className={base}>Removal requested</span>;
  if (photo.hidden) return <span className={base}>Hidden</span>;
  if (photo.wrongMatchReports > 0) {
    return (
      <span className={base}>
        <Flag className="size-3" /> {photo.wrongMatchReports}
      </span>
    );
  }
  return (
    <span className={cn(base, "tabular opacity-0 transition-opacity group-hover:opacity-100")}>
      {photo.faceCount ?? 0} {photo.faceCount === 1 ? "face" : "faces"}
    </span>
  );
}
