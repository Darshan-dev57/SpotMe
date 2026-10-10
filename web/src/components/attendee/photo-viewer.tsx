"use client";

import { useEffect } from "react";
import { ChevronLeft, ChevronRight, Download, X } from "lucide-react";
import { Match, mediaUrl } from "@/lib/api";

/**
 * Full-screen look at one matched photo, so a guest can check it is really them before
 * downloading. Arrow keys or the side buttons step through the results, Escape closes.
 */
export function PhotoViewer({
  photos,
  index,
  onIndex,
  onClose,
}: {
  photos: Match[];
  index: number;
  onIndex: (i: number) => void;
  onClose: () => void;
}) {
  const photo = photos[index];
  const hasPrev = index > 0;
  const hasNext = index < photos.length - 1;

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") onClose();
      if (e.key === "ArrowLeft" && hasPrev) onIndex(index - 1);
      if (e.key === "ArrowRight" && hasNext) onIndex(index + 1);
    }
    window.addEventListener("keydown", onKey);
    const overflow = document.body.style.overflow;
    document.body.style.overflow = "hidden"; // no page scrolling behind the viewer
    return () => {
      window.removeEventListener("keydown", onKey);
      document.body.style.overflow = overflow;
    };
  }, [index, hasPrev, hasNext, onIndex, onClose]);

  if (!photo) return null;

  const round =
    "flex size-11 items-center justify-center rounded-full bg-foreground/70 text-background ring-1 ring-background/25 hover:bg-foreground/90";

  return (
    <div role="dialog" aria-modal="true" aria-label="Photo" className="fixed inset-0 z-50 flex flex-col bg-foreground">
      <div className="flex items-center justify-between px-3 py-3">
        <span className="tabular px-2 text-sm text-background/70">
          {index + 1} / {photos.length}
        </span>
        <div className="flex gap-2">
          <a href={mediaUrl(photo.downloadUrl)} className={round} aria-label="Download this photo">
            <Download className="size-5" />
          </a>
          <button type="button" onClick={onClose} className={round} aria-label="Close" autoFocus>
            <X className="size-5" />
          </button>
        </div>
      </div>

      <div className="relative flex min-h-0 flex-1 items-center justify-center px-2 pb-6">
        {/* the full photo: shown with a thumbnail underneath while it loads */}
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img
          key={photo.id}
          src={mediaUrl(photo.downloadUrl)}
          alt=""
          className="max-h-full max-w-full rounded-md object-contain"
          style={{ backgroundImage: `url(${mediaUrl(photo.thumbUrl)})`, backgroundSize: "cover" }}
        />
        {hasPrev && (
          <button type="button" onClick={() => onIndex(index - 1)} className={`${round} absolute left-3`} aria-label="Previous photo">
            <ChevronLeft className="size-5" />
          </button>
        )}
        {hasNext && (
          <button type="button" onClick={() => onIndex(index + 1)} className={`${round} absolute right-3`} aria-label="Next photo">
            <ChevronRight className="size-5" />
          </button>
        )}
      </div>
    </div>
  );
}
