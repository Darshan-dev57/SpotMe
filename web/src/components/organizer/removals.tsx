"use client";

import { useEffect, useState } from "react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { ApiError, RemovalRequest, api, mediaUrl } from "@/lib/api";
import { formatDate, plural } from "@/lib/format";

const STATE: Record<RemovalRequest["status"], string> = {
  received: "Waiting for you. The photos are hidden from everyone meanwhile.",
  approved: "Approved. Deleting files and face data...",
  rejected: "Rejected. The photos are visible again.",
  completed: "Done. Files, thumbnails and face data deleted.",
};

/**
 * A guest asked for photos to be taken down. They are hidden already; approving deletes them
 * for good, rejecting puts them back. A lookalike selfie alone can never delete anything.
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

  async function decide(id: string, decision: "approve" | "reject") {
    try {
      await api(`/api/events/${eventId}/removals/${id}/${decision}`, { method: "POST", token });
      toast.success(decision === "approve" ? "Approved. Deleting now." : "Rejected. Photos restored.");
      load();
      onChanged();
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : "That didn't work.");
    }
  }

  if (!requests) return null;
  if (requests.length === 0) {
    return <p className="text-muted-foreground">No removal requests. Guests can ask from their results page.</p>;
  }

  return (
    <ul className="grid gap-4">
      {requests.map((r) => (
        <li key={r.id} className="rounded-xl border bg-card p-4 sm:p-5">
          <div className="flex flex-wrap items-baseline justify-between gap-2">
            <p className="font-medium">
              {r.requestedBy ?? "A guest"} asked to remove {plural(r.photos.length, "photo")}
            </p>
            <span className="text-sm text-muted-foreground">{formatDate(r.createdAt)}</span>
          </div>
          {r.note && <p className="mt-2 text-sm leading-relaxed">&ldquo;{r.note}&rdquo;</p>}
          <div className="mt-3 flex gap-1.5 overflow-x-auto">
            {r.photos.map((p) =>
              p.thumbUrl ? (
                // eslint-disable-next-line @next/next/no-img-element
                <img key={p.id} src={mediaUrl(p.thumbUrl)} alt="" className="size-20 shrink-0 rounded-md object-cover" />
              ) : (
                <div key={p.id} className="flex size-20 shrink-0 items-center justify-center rounded-md bg-muted text-xs text-muted-foreground">
                  Deleted
                </div>
              ),
            )}
          </div>
          <p className="mt-3 text-sm text-muted-foreground">{STATE[r.status]}</p>
          {r.status === "received" && (
            <div className="mt-3 flex flex-wrap gap-2">
              <Button variant="destructive" onClick={() => decide(r.id, "approve")}>
                Delete permanently
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
