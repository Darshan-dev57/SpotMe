"use client";

import { useEffect, useRef, useState } from "react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { ApiError, RemovalRequest, api, mediaUrl } from "@/lib/api";
import { formatDate, plural } from "@/lib/format";

const STATE: Record<RemovalRequest["status"], string> = {
  received: "Hidden from everyone until you decide.",
  approved: "Approved. Working on it...",
  rejected: "Rejected. The photos are back.",
  completed: "Done.",
};

/**
 * A guest asked to be removed. Solo photos are deleted when approved; with "erase", the guest is
 * also cut out of group photos, which stay hidden until the edited copy is in place.
 * Rejecting puts everything back. A lookalike selfie alone can never delete anything.
 */
export function Removals({ eventId, token, onChanged }: { eventId: string; token: string; onChanged: () => void }) {
  const [requests, setRequests] = useState<RemovalRequest[] | null>(null);

  const [version, setVersion] = useState(0);
  const load = () => setVersion((v) => v + 1);

  useEffect(() => {
    api<RemovalRequest[]>(`/api/events/${eventId}/removals`, { token })
      .then(setRequests)
      .catch(() => undefined);
  }, [eventId, token, version]);

  // refresh while a purge is running so "Deleting..." turns into "Done"
  const purging = !!requests?.some((r) => r.status === "approved");
  useEffect(() => {
    if (!purging) return;
    const t = setInterval(() => setVersion((v) => v + 1), 2000);
    return () => clearInterval(t);
  }, [purging]);

  // once a purge finishes, the event's photo counts have changed too
  const wasPurging = useRef(false);
  useEffect(() => {
    if (wasPurging.current && !purging) onChanged();
    wasPurging.current = purging;
  }, [purging, onChanged]);

  async function decide(id: string, decision: "approve" | "reject") {
    try {
      await api(`/api/events/${eventId}/removals/${id}/${decision}`, { method: "POST", token });
      toast.success(decision === "approve" ? "Approved." : "Rejected. Photos restored.");
      load();
      onChanged();
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : "That didn't work.");
    }
  }

  if (!requests) return null;
  if (requests.length === 0) {
    return <p className="text-muted-foreground">No removal requests.</p>;
  }

  return (
    <ul className="grid gap-4">
      {requests.map((r) => (
        <li key={r.id} className="rounded-xl border bg-card p-4 sm:p-5">
          <div className="flex flex-wrap items-baseline justify-between gap-2">
            <p className="font-medium">
              {r.requestedBy ?? "A guest"} asked to {r.mode === "erase" ? "be erased" : "be removed"}
            </p>
            <span className="text-sm text-muted-foreground">{formatDate(r.createdAt)}</span>
          </div>
          {r.note && <p className="mt-2 text-sm leading-relaxed">&ldquo;{r.note}&rdquo;</p>}
          {r.photos.length > 0 && <Thumbs label={`Delete ${plural(r.photos.length, "photo")}`} photos={r.photos} />}
          {r.edits.length > 0 && (
            <Thumbs
              label={`Erase them from ${plural(r.edits.length, "group photo")}`}
              photos={r.edits.map((e) => ({ id: e.photoId, thumbUrl: e.thumbUrl }))}
            />
          )}
          <p className="mt-3 text-sm text-muted-foreground">{STATE[r.status]}</p>
          {r.status === "received" && (
            <div className="mt-3 flex flex-wrap gap-2">
              <Button variant="destructive" onClick={() => decide(r.id, "approve")}>
                {r.edits.length > 0 ? "Approve" : "Delete permanently"}
              </Button>
              <Button variant="outline" onClick={() => decide(r.id, "reject")}>
                Keep the photos
              </Button>
            </div>
          )}
        </li>
      ))}
    </ul>
  );
}

function Thumbs({ label, photos }: { label: string; photos: { id: string; thumbUrl?: string }[] }) {
  return (
    <div className="mt-3">
      <p className="text-sm text-muted-foreground">{label}</p>
      <div className="mt-1.5 flex gap-1.5 overflow-x-auto">
        {photos.map((p) =>
          p.thumbUrl ? (
            // eslint-disable-next-line @next/next/no-img-element
            <img key={p.id} src={mediaUrl(p.thumbUrl)} alt="" className="size-20 shrink-0 rounded-md object-cover" />
          ) : (
            <div
              key={p.id}
              className="flex size-20 shrink-0 items-center justify-center rounded-md bg-muted text-xs text-muted-foreground"
            >
              Deleted
            </div>
          ),
        )}
      </div>
    </div>
  );
}
