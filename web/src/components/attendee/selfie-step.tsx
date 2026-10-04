"use client";

import { useRef, useState } from "react";
import { Camera, ImageUp } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";
import { ApiError, Notice, SearchResult, api } from "@/lib/api";
import { shrinkImage } from "@/lib/image";

/** One selfie, sent as the raw request body and matched in memory on the server. */
export function SelfieStep({
  notice,
  token,
  onResult,
  onLostAccess,
}: {
  notice: Notice;
  token: string;
  onResult: (r: SearchResult) => void;
  onLostAccess: () => void;
}) {
  const camera = useRef<HTMLInputElement>(null);
  const library = useRef<HTMLInputElement>(null);
  const [preview, setPreview] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function search(file: File | undefined) {
    if (!file) return;
    setError(null);
    setBusy(true);
    setPreview(URL.createObjectURL(file));
    try {
      const body = await shrinkImage(file);
      const result = await api<SearchResult>("/api/attendee/search", { token, body, contentType: "image/jpeg" });
      onResult(result);
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) {
        onLostAccess();
        return;
      }
      setError(e instanceof ApiError ? e.message : "Search failed. Try again.");
      setBusy(false);
    }
  }

  if (!notice.published) {
    return (
      <div className="pt-8">
        <h1 className="text-2xl font-semibold">{notice.eventTitle}</h1>
        <p className="mt-2 max-w-prose text-muted-foreground">
          You&apos;re in. The organizer is still reviewing the album; come back to this link once it&apos;s published.
        </p>
      </div>
    );
  }

  return (
    <section className="pt-6">
      <h1 className="text-3xl font-semibold">Take a selfie</h1>
      <p className="mt-2 max-w-prose text-muted-foreground">
        Face the camera in good light, with only you in the frame. Glasses are fine; sunglasses and masks make it
        harder.
      </p>

      <div className="mt-8 grid gap-6 sm:grid-cols-[14rem_1fr] sm:items-center">
        <div className="relative mx-auto aspect-[3/4] w-48 overflow-hidden rounded-2xl bg-muted sm:mx-0 sm:w-56">
          {preview ? (
            // eslint-disable-next-line @next/next/no-img-element
            <img src={preview} alt="Your selfie" className={busy ? "h-full w-full object-cover opacity-70" : "h-full w-full object-cover"} />
          ) : (
            <svg viewBox="0 0 120 160" className="h-full w-full text-input" aria-hidden="true">
              <ellipse cx="60" cy="68" rx="30" ry="38" fill="none" stroke="currentColor" strokeWidth="2" strokeDasharray="5 6" />
              <path d="M20 150c6-26 22-38 40-38s34 12 40 38" fill="none" stroke="currentColor" strokeWidth="2" strokeDasharray="5 6" />
            </svg>
          )}
          {busy && (
            <div className="absolute inset-0 flex items-end justify-center bg-gradient-to-t from-foreground/70 to-transparent p-4">
              <span className="flex items-center gap-2 text-sm text-background">
                <Spinner /> Looking through the album
              </span>
            </div>
          )}
        </div>

        <div className="grid gap-3 sm:justify-items-start">
          {error && (
            <p className="rounded-lg bg-accent px-4 py-3 text-sm leading-relaxed" role="alert">
              {error}
            </p>
          )}
          <Button size="lg" onClick={() => camera.current?.click()} disabled={busy}>
            <Camera /> {preview ? "Take another" : "Open camera"}
          </Button>
          <Button size="lg" variant="outline" onClick={() => library.current?.click()} disabled={busy}>
            <ImageUp /> Choose a photo
          </Button>
          <p className="text-sm text-muted-foreground">The selfie is used for this one search and then discarded.</p>
        </div>
      </div>

      <input ref={camera} type="file" accept="image/*" capture="user" hidden onChange={(e) => search(e.target.files?.[0])} />
      <input ref={library} type="file" accept="image/jpeg,image/png" hidden onChange={(e) => search(e.target.files?.[0])} />
    </section>
  );
}
