"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { ArrowRight, Check, ImageUp, Images } from "lucide-react";
import { PhotoViewer } from "@/components/attendee/photo-viewer";
import { Button } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";
import { ApiError, GuestPhoto, Match, Notice, api, mediaUrl } from "@/lib/api";
import { SAMPLE_FACES, SampleFace, SampleSummary, newSampleInvite } from "@/lib/sample";
import { cn } from "@/lib/utils";

const SESSION_KEY = "spotme.sample.session";

type Found = { face: SampleFace; matches: Match[]; album: GuestPhoto[]; total: number; stillIndexing: boolean };

/**
 * "Try with a sample face": the visitor taps one of the Crew-1 astronauts and sees which photos of the
 * sample album SpotMe finds them in. The search is the real one: the portrait is sent to the same API a
 * guest's selfie goes to. Only NASA portraits are sent here, nothing of the visitor's.
 */
export function SampleTry() {
  const [summary, setSummary] = useState<SampleSummary | null | undefined>(undefined);
  const [busy, setBusy] = useState<string | null>(null);
  const [found, setFound] = useState<Found | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [viewing, setViewing] = useState<number | null>(null);
  const [opening, setOpening] = useState(false);

  useEffect(() => {
    api<SampleSummary>("/api/sample")
      .then(setSummary)
      .catch(() => setSummary(null));
  }, []);

  /** A guest session on the sample event, made once per tab: invite, read the notice, agree. */
  async function session(fresh = false): Promise<string> {
    const saved = fresh ? null : sessionStorage.getItem(SESSION_KEY);
    if (saved) return saved;
    const invite = await newSampleInvite();
    const notice = await api<Notice>(`/api/join/${encodeURIComponent(invite)}`);
    const { sessionToken } = await api<{ sessionToken: string }>(`/api/join/${encodeURIComponent(invite)}/consent`, {
      json: { noticeVersion: notice.noticeVersion, agree: true },
    });
    sessionStorage.setItem(SESSION_KEY, sessionToken);
    return sessionToken;
  }

  async function searchAs(face: SampleFace, token: string): Promise<Found> {
    const body = await (await fetch(face.src)).blob();
    const result = await api<{ count: number; photos: Match[]; stillIndexing: boolean }>("/api/attendee/search", {
      token,
      body,
      contentType: "image/jpeg",
    });
    const page = await api<{ total: number; photos: GuestPhoto[] }>("/api/attendee/event-photos?offset=0", { token });
    return { face, matches: result.photos, album: page.photos, total: page.total, stillIndexing: result.stillIndexing };
  }

  async function tryFace(face: SampleFace) {
    setBusy(face.id);
    setError(null);
    try {
      let token = await session();
      try {
        setFound(await searchAs(face, token));
      } catch (e) {
        if (!(e instanceof ApiError && e.status === 401)) throw e;
        token = await session(true); // the saved session ended: start a new one and search once more
        setFound(await searchAs(face, token));
      }
      requestAnimationFrame(() =>
        document.getElementById("sample-results")?.scrollIntoView({ behavior: "smooth", block: "start" }),
      );
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "The search did not go through. Try again.");
    } finally {
      setBusy(null);
    }
  }

  /** "Use my own selfie": the full guest flow on the sample event, notice first. */
  async function openOwn() {
    setOpening(true);
    try {
      const invite = await newSampleInvite();
      // a full page load, so the join page sees the token in the "#" from its first render
      // eslint-disable-next-line @next/next/no-location-assign-relative-destination
      window.location.assign(`/join#${invite}`);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Could not open the sample event.");
      setOpening(false);
    }
  }

  if (summary === null) {
    return (
      <p className="mt-6 rounded-lg bg-muted px-4 py-3 text-sm">
        The sample album is not available right now. Create an event to try SpotMe with your own photos.
      </p>
    );
  }

  const matchIds = new Set(found?.matches.map((m) => m.id) ?? []);
  // the album with this person's photos first, then the rest; a match the album page lacks is still shown
  const rest = found ? found.album.filter((p) => !matchIds.has(p.id)) : [];
  const shown: GuestPhoto[] = found ? [...found.matches, ...rest] : [];
  const total = found ? Math.max(found.total, found.matches.length + rest.length) : (summary?.photoCount ?? 0);

  return (
    <div>
      <ul className="mt-6 grid grid-cols-2 gap-3 sm:grid-cols-4 sm:gap-4">
        {SAMPLE_FACES.map((face) => {
          const active = found?.face.id === face.id;
          return (
            <li key={face.id}>
              <button
                type="button"
                onClick={() => tryFace(face)}
                disabled={busy !== null}
                aria-pressed={active}
                className={cn(
                  "group block w-full rounded-xl border bg-card p-2 text-left transition-shadow disabled:cursor-wait",
                  "hover:ring-2 hover:ring-primary focus-visible:ring-2 focus-visible:ring-primary focus-visible:outline-none",
                  active && "ring-[3px] ring-primary",
                )}
              >
                <span className="relative block aspect-[3/4] overflow-hidden rounded-lg bg-muted">
                  {/* eslint-disable-next-line @next/next/no-img-element */}
                  <img src={face.src} alt={`Portrait of ${face.name}`} className="h-full w-full object-cover" />
                  {busy === face.id && (
                    <span className="absolute inset-0 flex items-end justify-center bg-gradient-to-t from-foreground/75 to-transparent p-3">
                      <span className="flex items-center gap-2 text-sm text-background">
                        <Spinner /> Searching
                      </span>
                    </span>
                  )}
                </span>
                <span className="mt-2 block px-1 text-sm leading-tight font-semibold">{face.name}</span>
                <span className="block px-1 pb-1 text-xs text-primary">
                  {active ? "Showing results" : `Find ${face.first}`}
                  {!active && <ArrowRight className="ml-1 inline size-3" aria-hidden="true" />}
                </span>
              </button>
            </li>
          );
        })}
      </ul>

      {error && (
        <p className="mt-4 rounded-lg bg-accent px-4 py-3 text-sm leading-relaxed" role="alert">
          {error}
        </p>
      )}

      {found && (
        <div id="sample-results" className="mt-8 scroll-mt-4 rounded-2xl border bg-card p-4 sm:p-6">
          <div className="flex items-center gap-3">
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src={found.face.src} alt="" className="size-12 shrink-0 rounded-full object-cover ring-2 ring-primary" />
            <div>
              <p className="text-xl leading-tight font-semibold sm:text-2xl">
                {found.matches.length > 0
                  ? `Found ${found.face.first} in ${found.matches.length} of ${total} photos`
                  : `No photos of ${found.face.first} found yet`}
              </p>
              <p className="mt-0.5 text-sm text-muted-foreground">
                Matched photos are outlined. The rest of the album is faded.
              </p>
            </div>
          </div>

          {found.stillIndexing && (
            <p className="mt-4 rounded-lg bg-muted px-4 py-3 text-sm">
              The album is still being processed, so some photos may be missing. Try again in a minute.
            </p>
          )}

          <ul className="mt-5 grid grid-cols-3 gap-1.5 sm:grid-cols-5 sm:gap-2 lg:grid-cols-6">
            {shown.map((p, i) => {
              const hit = matchIds.has(p.id);
              return (
                <li key={p.id} className={hit ? "develop" : undefined} style={hit ? { animationDelay: `${Math.min(i, 12) * 70}ms` } : undefined}>
                  <button
                    type="button"
                    onClick={() => setViewing(i)}
                    aria-label={hit ? `Photo with ${found.face.name}` : "Photo from the album"}
                    className={cn(
                      "relative block aspect-square w-full overflow-hidden rounded-md bg-muted",
                      hit ? "ring-[3px] ring-primary" : "opacity-40 grayscale",
                    )}
                  >
                    {/* eslint-disable-next-line @next/next/no-img-element */}
                    <img src={mediaUrl(p.thumbUrl)} alt="" loading="lazy" className="h-full w-full object-cover" />
                    {hit && (
                      <span className="absolute top-1.5 left-1.5 flex size-5 items-center justify-center rounded-full bg-primary text-primary-foreground">
                        <Check className="size-3.5" aria-hidden="true" />
                      </span>
                    )}
                  </button>
                </li>
              );
            })}
          </ul>
          <p className="mt-4 text-sm text-muted-foreground">
            Tap a photo to see it larger. Try another astronaut above to see a different set.
          </p>
        </div>
      )}

      <div className="mt-8 rounded-2xl border border-dashed p-4 sm:p-6">
        <h3 className="flex items-center gap-2 font-semibold">
          <ImageUp className="size-4 text-primary" aria-hidden="true" /> What about my own selfie?
        </h3>
        <p className="mt-2 max-w-prose text-sm leading-relaxed text-muted-foreground">
          SpotMe only finds you in photos you are actually in. You are not in this NASA album, so your selfie
          will find nothing here, and that means it is working. To see your own photos, create a free event,
          upload a few group photos of you and your friends, then search with your selfie.
        </p>
        <div className="mt-4 flex flex-wrap gap-3">
          <Button asChild>
            <Link href="/organizer">
              <Images /> Create your own event
            </Link>
          </Button>
          <Button variant="outline" onClick={openOwn} disabled={opening}>
            {opening && <Spinner />} Search this album with my selfie
          </Button>
        </div>
      </div>

      {found && viewing !== null && (
        <PhotoViewer photos={shown} index={viewing} onIndex={setViewing} onClose={() => setViewing(null)} />
      )}
    </div>
  );
}
