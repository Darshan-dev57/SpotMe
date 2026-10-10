"use client";

import { useState } from "react";
import { Check, Download, Expand, Flag, RefreshCw, Trash2 } from "lucide-react";
import { toast } from "sonner";
import { PhotoViewer } from "@/components/attendee/photo-viewer";
import { Field } from "@/components/field";
import { Button } from "@/components/ui/button";
import { Empty, EmptyDescription, EmptyHeader, EmptyTitle } from "@/components/ui/empty";
import { Spinner } from "@/components/ui/spinner";
import { Textarea } from "@/components/ui/textarea";
import { ApiError, Match, Notice, SearchResult, api, apiBlob, mediaUrl, saveBlob } from "@/lib/api";
import { plural } from "@/lib/format";
import { cn } from "@/lib/utils";

type Mode = "browse" | "remove" | "removed";

export function Results({
  notice,
  token,
  result,
  onAgain,
  onLeft,
}: {
  notice: Notice;
  token: string;
  result: SearchResult;
  onAgain: () => void;
  onLeft: () => void;
}) {
  const [photos, setPhotos] = useState<Match[]>(result.photos);
  const [selected, setSelected] = useState<Set<string>>(new Set(result.photos.map((p) => p.id)));
  const [mode, setMode] = useState<Mode>("browse");
  const [busy, setBusy] = useState(false);
  const [leaving, setLeaving] = useState(false);
  const [viewing, setViewing] = useState<number | null>(null);

  function toggle(id: string) {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  async function downloadZip() {
    setBusy(true);
    try {
      const blob = await apiBlob("/api/attendee/download", { token, json: { photoIds: [...selected] } });
      saveBlob(blob, `${notice.eventTitle.replace(/[^a-z0-9]+/gi, "-").toLowerCase()}-photos.zip`);
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : "Download failed.");
    }
    setBusy(false);
  }

  async function wrongMatch(id: string) {
    await api("/api/attendee/wrong-match", { token, json: { photoId: id } });
    setPhotos((prev) => prev.filter((p) => p.id !== id));
    setSelected((prev) => {
      const next = new Set(prev);
      next.delete(id);
      return next;
    });
    toast.success("Thanks. That photo is hidden from your results.");
  }

  // Removal starts with nothing selected, so a guest only picks the photos to take down.
  function startRemove() {
    setSelected(new Set());
    setMode("remove");
  }

  function cancelRemove() {
    setSelected(new Set(photos.map((p) => p.id)));
    setMode("browse");
  }

  async function requestRemoval(form: FormData) {
    setBusy(true);
    try {
      await api("/api/attendee/removals", { token, json: { photoIds: [...selected], note: form.get("note") || null } });
      const left = photos.filter((p) => !selected.has(p.id));
      setPhotos(left);
      setSelected(new Set(left.map((p) => p.id)));
      setMode("removed");
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : "Could not send the request.");
    }
    setBusy(false);
  }

  async function leave() {
    await api("/api/attendee/withdraw", { method: "POST", token });
    onLeft();
  }

  return (
    <section className="pt-6">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-3xl font-semibold">
            {photos.length > 0 ? `${plural(photos.length, "photo")} you're probably in` : "No confident matches"}
          </h1>
          <p className="mt-1 text-muted-foreground">From {notice.eventTitle}</p>
        </div>
        <Button variant="ghost" onClick={onAgain}>
          <RefreshCw /> New selfie
        </Button>
      </div>

      {result.stillIndexing && (
        <p className="mt-4 rounded-lg bg-muted px-4 py-3 text-sm">
          Some photos are still being processed. Search again in a few minutes to see them too.
        </p>
      )}

      {mode === "removed" && (
        <p className="mt-4 rounded-lg bg-accent px-4 py-3 text-sm leading-relaxed" role="status">
          Request sent. Those photos are hidden from everyone now, and the organizer will delete them for good after
          checking.
        </p>
      )}

      {photos.length === 0 ? (
        <Empty className="mt-8 border">
          <EmptyHeader>
            <EmptyTitle>Nothing close enough</EmptyTitle>
            <EmptyDescription>
              SpotMe only shows photos it is confident about. Try a clearer, front-facing selfie, or ask the organizer.
            </EmptyDescription>
          </EmptyHeader>
        </Empty>
      ) : (
        <>
          <p className="mt-6 text-sm text-muted-foreground">
            {mode === "remove" ? "Select the photos you want taken down." : "Tap a photo to select or unselect it."}
          </p>
          <ul className="mt-3 grid grid-cols-2 gap-2 sm:grid-cols-3">
            {photos.map((p, i) => {
              const on = selected.has(p.id);
              return (
                <li key={p.id} className="develop" style={{ animationDelay: `${Math.min(i, 12) * 70}ms` }}>
                  <div className="group relative">
                    <button
                      type="button"
                      onClick={() => toggle(p.id)}
                      aria-pressed={on}
                      aria-label={on ? "Selected photo" : "Photo"}
                      className={cn(
                        "block aspect-[4/3] w-full overflow-hidden rounded-lg bg-muted ring-offset-2 ring-offset-background transition-shadow",
                        on && (mode === "remove" ? "ring-[3px] ring-destructive" : "ring-[3px] ring-primary"),
                      )}
                    >
                      {/* eslint-disable-next-line @next/next/no-img-element */}
                      <img src={mediaUrl(p.thumbUrl)} alt="" className="h-full w-full object-cover" />
                    </button>
                    {on && (
                      <span
                        className={cn(
                          "pointer-events-none absolute top-2 left-2 flex size-6 items-center justify-center rounded-full text-primary-foreground",
                          mode === "remove" ? "bg-destructive" : "bg-primary",
                        )}
                      >
                        <Check className="size-4" />
                      </span>
                    )}
                    {mode !== "remove" && (
                      <div className="mt-1 flex justify-end gap-0.5">
                        <button
                          type="button"
                          onClick={() => setViewing(i)}
                          className="flex size-9 items-center justify-center rounded-full text-muted-foreground hover:bg-muted hover:text-foreground"
                          aria-label="View larger"
                        >
                          <Expand className="size-4" />
                        </button>
                        <a
                          href={mediaUrl(p.downloadUrl)}
                          className="flex size-9 items-center justify-center rounded-full text-muted-foreground hover:bg-muted hover:text-foreground"
                          aria-label="Download this photo"
                        >
                          <Download className="size-4" />
                        </a>
                        <button
                          type="button"
                          onClick={() => wrongMatch(p.id)}
                          className="flex size-9 items-center justify-center rounded-full text-muted-foreground hover:bg-muted hover:text-foreground"
                          aria-label="Not me: report a wrong match"
                          title="Not me"
                        >
                          <Flag className="size-4" />
                        </button>
                      </div>
                    )}
                  </div>
                </li>
              );
            })}
          </ul>

          {mode !== "remove" ? (
            <div className="sticky bottom-0 mt-6 -mx-5 flex flex-wrap items-center gap-3 border-t bg-background/95 px-5 py-3 backdrop-blur sm:static sm:mx-0 sm:border-0 sm:bg-transparent sm:px-0">
              <Button size="lg" onClick={downloadZip} disabled={selected.size === 0 || busy}>
                {busy ? <Spinner /> : <Download />} Download {selected.size > 0 ? plural(selected.size, "photo") : ""}
              </Button>
              <Button size="lg" variant="ghost" onClick={startRemove}>
                <Trash2 /> Remove photos of me
              </Button>
            </div>
          ) : (
            <form action={requestRemoval} className="mt-6 grid max-w-xl gap-4 rounded-xl border bg-card p-5">
              <h2 className="font-medium">Ask for {plural(selected.size, "photo")} to be removed</h2>
              <p className="text-sm leading-relaxed text-muted-foreground">
                They are hidden from everyone right away. When the organizer approves, the whole photo is deleted, so
                other people in it lose it too.
              </p>
              <Field id="note" label="Note to the organizer (optional)">
                <Textarea id="note" name="note" rows={2} maxLength={500} />
              </Field>
              <div className="flex flex-wrap gap-2">
                <Button type="submit" variant="destructive" disabled={selected.size === 0 || busy}>
                  {busy && <Spinner />} Send request
                </Button>
                <Button type="button" variant="ghost" onClick={cancelRemove}>
                  Cancel
                </Button>
              </div>
            </form>
          )}
        </>
      )}

      <div className="mt-16 border-t pt-6">
        <h2 className="font-medium">Leave this event</h2>
        <p className="mt-1 max-w-prose text-sm text-muted-foreground">
          Withdraws your consent. Your link stops working and you can&apos;t search again.
        </p>
        {leaving ? (
          <div className="mt-3 flex gap-2">
            <Button variant="destructive" size="sm" onClick={leave}>
              Yes, withdraw
            </Button>
            <Button variant="ghost" size="sm" onClick={() => setLeaving(false)}>
              Cancel
            </Button>
          </div>
        ) : (
          <Button variant="outline" size="sm" className="mt-3" onClick={() => setLeaving(true)}>
            Withdraw consent
          </Button>
        )}
      </div>
      {viewing !== null && (
        <PhotoViewer photos={photos} index={viewing} onIndex={setViewing} onClose={() => setViewing(null)} />
      )}
    </section>
  );
}
