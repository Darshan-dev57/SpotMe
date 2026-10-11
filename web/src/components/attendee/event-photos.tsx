"use client";

import { useEffect, useState } from "react";
import { Download, Expand, Users } from "lucide-react";
import { PhotoViewer } from "@/components/attendee/photo-viewer";
import { Button } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";
import { EventPhotos as EventPhotosPage, GuestPhoto, api, mediaUrl } from "@/lib/api";
import { plural } from "@/lib/format";
import { withoutMatches } from "@/lib/photos";

/**
 * Photos the organizer shared with everyone (venue, stage, crowd). Shown under the guest's own
 * matches; photos already in the matches are left out so nothing appears twice.
 */
export function EventPhotos({ token, matchedIds }: { token: string; matchedIds: string[] }) {
  const [photos, setPhotos] = useState<GuestPhoto[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [viewing, setViewing] = useState<number | null>(null);

  useEffect(() => {
    let current = true;
    api<EventPhotosPage>("/api/attendee/event-photos?offset=0", { token })
      .then((page) => {
        if (!current) return;
        setTotal(page.total);
        setPhotos(page.photos);
      })
      // the section is optional: on an error it simply stays hidden
      .catch(() => undefined)
      .finally(() => current && setLoading(false));
    return () => {
      current = false;
    };
  }, [token]);

  async function loadMore() {
    setLoading(true);
    try {
      const page = await api<EventPhotosPage>(`/api/attendee/event-photos?offset=${photos.length}`, { token });
      setTotal(page.total);
      setPhotos((prev) => [...prev, ...page.photos]);
    } catch {
      // keep what is already shown
    }
    setLoading(false);
  }

  const shown = withoutMatches(photos, matchedIds);
  if (total === 0 || (shown.length === 0 && photos.length >= total)) return null;

  return (
    <section className="mt-12" aria-labelledby="event-photos">
      <h2 id="event-photos" className="flex items-center gap-2 text-xl font-semibold">
        <Users className="size-5" /> Event photos
      </h2>
      <p className="mt-1 text-sm text-muted-foreground">
        {plural(total, "photo")} the organizer shared with every guest.
      </p>
      <ul className="mt-4 grid grid-cols-3 gap-1.5 sm:grid-cols-4">
        {shown.map((p, i) => (
          <li key={p.id} className="group relative">
            <button
              type="button"
              onClick={() => setViewing(i)}
              className="block aspect-square w-full overflow-hidden rounded-md bg-muted"
              aria-label="View larger"
            >
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img src={mediaUrl(p.thumbUrl)} alt="" loading="lazy" className="h-full w-full object-cover" />
              <Expand className="absolute top-1.5 right-1.5 size-4 text-white opacity-0 drop-shadow transition-opacity group-hover:opacity-100" />
            </button>
            <a
              href={mediaUrl(p.downloadUrl)}
              className="absolute right-1 bottom-1 flex size-8 items-center justify-center rounded-full bg-background/85 text-foreground shadow-sm"
              aria-label="Download this photo"
            >
              <Download className="size-4" />
            </a>
          </li>
        ))}
      </ul>
      {photos.length < total && (
        <Button variant="outline" className="mt-4" onClick={loadMore} disabled={loading}>
          {loading && <Spinner />} Show more
        </Button>
      )}
      {viewing !== null && (
        <PhotoViewer photos={shown} index={viewing} onIndex={setViewing} onClose={() => setViewing(null)} />
      )}
    </section>
  );
}
