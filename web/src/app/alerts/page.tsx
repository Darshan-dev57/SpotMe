"use client";

import { useCallback, useEffect, useState } from "react";
import { BellOff, Check, Download } from "lucide-react";
import { toast } from "sonner";
import { SiteHeader } from "@/components/brand";
import { Button } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";
import { AlertCheck, ApiError, api, mediaUrl } from "@/lib/api";
import { formatDate, plural } from "@/lib/format";
import { useHash } from "@/lib/hooks";

type State =
  { kind: "loading" } | { kind: "ready"; data: AlertCheck } | { kind: "gone"; message: string } | { kind: "off" };

/** The private link from "Notify me". The token sits after "#", so it never reaches a server log. */
export default function AlertsPage() {
  const hash = useHash();
  const [state, setState] = useState<State>({ kind: "loading" });
  const [busy, setBusy] = useState(false);

  const load = useCallback(() => {
    const check = hash
      ? api<AlertCheck>(`/api/alerts/${encodeURIComponent(hash)}`)
      : Promise.reject(new ApiError(404, "no_token", "This page needs your private link."));
    check
      .then((data) => setState({ kind: "ready", data }))
      .catch((e) => setState({ kind: "gone", message: e instanceof ApiError ? e.message : "Something went wrong." }));
  }, [hash]);

  useEffect(() => {
    if (hash !== undefined) load(); // undefined: not in the browser yet
  }, [hash, load]);

  async function markSeen(checkedAt: string) {
    setBusy(true);
    try {
      await api(`/api/alerts/${encodeURIComponent(hash ?? "")}/seen`, { json: { until: checkedAt } });
      load();
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : "That didn't work.");
    }
    setBusy(false);
  }

  async function turnOff() {
    setBusy(true);
    try {
      await api(`/api/alerts/${encodeURIComponent(hash ?? "")}`, { method: "DELETE" });
      setState({ kind: "off" });
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : "That didn't work.");
    }
    setBusy(false);
  }

  return (
    <>
      <SiteHeader />
      <main className="mx-auto w-full max-w-3xl px-5 pt-8 pb-20 sm:px-8">
        {state.kind === "loading" && (
          <div className="flex items-center gap-2 text-muted-foreground">
            <Spinner /> Checking for new photos
          </div>
        )}

        {state.kind === "gone" && (
          <>
            <h1 className="text-2xl font-semibold">This link no longer works</h1>
            <p className="mt-2 max-w-prose text-muted-foreground">{state.message}</p>
          </>
        )}

        {state.kind === "off" && (
          <>
            <h1 className="text-2xl font-semibold">Alerts are off</h1>
            <p className="mt-2 max-w-prose text-muted-foreground">Your face data for alerts is deleted.</p>
          </>
        )}

        {state.kind === "ready" && (
          <section>
            <h1 className="text-3xl font-semibold">
              {state.data.newCount > 0 ? `${plural(state.data.newCount, "new photo")}` : "No new photos yet"}
            </h1>
            <p className="mt-1 text-muted-foreground">
              {state.data.eventTitle} · since {formatDate(state.data.seenUntil)}
            </p>

            {state.data.newCount > 0 && (
              <>
                <ul className="mt-6 grid grid-cols-2 gap-2 sm:grid-cols-3">
                  {state.data.photos.map((p) => (
                    <li key={p.id} className="relative">
                      {/* eslint-disable-next-line @next/next/no-img-element */}
                      <img
                        src={mediaUrl(p.thumbUrl)}
                        alt=""
                        className="aspect-[4/3] w-full rounded-lg bg-muted object-cover"
                      />
                      <a
                        href={mediaUrl(p.downloadUrl)}
                        className="absolute right-2 bottom-2 flex size-9 items-center justify-center rounded-full bg-background/90 text-foreground"
                        aria-label="Download this photo"
                      >
                        <Download className="size-4" />
                      </a>
                    </li>
                  ))}
                </ul>
                <Button className="mt-6" onClick={() => markSeen(state.data.checkedAt)} disabled={busy}>
                  <Check /> Mark as seen
                </Button>
              </>
            )}

            <div className="mt-12 border-t pt-6">
              <p className="text-sm text-muted-foreground">
                Checks this event until {formatDate(state.data.expiresAt)}.
              </p>
              <Button variant="outline" size="sm" className="mt-3" onClick={turnOff} disabled={busy}>
                <BellOff /> Turn off alerts
              </Button>
            </div>
          </section>
        )}
      </main>
    </>
  );
}
