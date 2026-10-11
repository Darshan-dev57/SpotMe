"use client";

import { useEffect, useRef, useState } from "react";
import { Camera, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";
import { cameraError, stopStream } from "@/lib/camera";

/**
 * In-page webcam for laptops: a mirrored preview and a "Take photo" button. The camera is
 * turned off as soon as the photo is taken, on Cancel, and when the component goes away.
 */
export function LiveCamera({ onPhoto, onCancel }: { onPhoto: (file: File) => void; onCancel: (error?: string) => void }) {
  const video = useRef<HTMLVideoElement>(null);
  const stream = useRef<MediaStream | null>(null);
  const [ready, setReady] = useState(false);

  useEffect(() => {
    let cancelled = false;
    navigator.mediaDevices
      .getUserMedia({ video: { facingMode: "user", width: { ideal: 1280 }, height: { ideal: 960 } }, audio: false })
      .then((s) => {
        if (cancelled) {
          stopStream(s);
          return;
        }
        stream.current = s;
        if (video.current) video.current.srcObject = s;
      })
      .catch((e) => onCancel(cameraError(e)));
    return () => {
      cancelled = true;
      stopStream(stream.current);
      stream.current = null;
    };
  }, [onCancel]);

  function take() {
    const v = video.current;
    if (!v || !v.videoWidth) return;
    const canvas = document.createElement("canvas");
    canvas.width = v.videoWidth;
    canvas.height = v.videoHeight;
    // the preview is mirrored like a mirror; the photo itself is not
    canvas.getContext("2d")?.drawImage(v, 0, 0);
    stopStream(stream.current);
    stream.current = null;
    if (v) v.srcObject = null;
    canvas.toBlob((blob) => {
      if (blob) onPhoto(new File([blob], "selfie.jpg", { type: "image/jpeg" }));
    }, "image/jpeg", 0.92);
  }

  return (
    <div className="mt-6 grid max-w-md gap-3">
      <div className="relative aspect-[4/3] overflow-hidden rounded-2xl bg-muted">
        <video
          ref={video}
          autoPlay
          playsInline
          muted
          onLoadedData={() => setReady(true)}
          className="h-full w-full -scale-x-100 object-cover"
          aria-label="Camera preview"
        />
        {!ready && (
          <span className="absolute inset-0 flex items-center justify-center gap-2 text-sm text-muted-foreground">
            <Spinner /> Starting camera
          </span>
        )}
      </div>
      <div className="flex gap-2">
        <Button size="lg" onClick={take} disabled={!ready}>
          <Camera /> Take photo
        </Button>
        <Button size="lg" variant="ghost" onClick={() => onCancel()}>
          <X /> Cancel
        </Button>
      </div>
    </div>
  );
}
