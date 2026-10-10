"use client";

import { useCallback, useEffect, useState } from "react";
import { FolderDown } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Progress } from "@/components/ui/progress";
import { Spinner } from "@/components/ui/spinner";
import { ApiError, type DriveImport, api } from "@/lib/api";
import { driveError, driveProgress, plural } from "@/lib/format";

const POLL_MS = 3000;

/**
 * Paste a public Google Drive folder link; the server lists the folder and imports the photos one by
 * one in the background. This only starts the import and shows its progress.
 */
export function DriveImporter({ eventId, token, onImported }: { eventId: string; token: string; onImported: () => void }) {
  const [link, setLink] = useState("");
  const [latest, setLatest] = useState<DriveImport | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [starting, setStarting] = useState(false);
  const running = latest?.status === "listing" || latest?.status === "importing";

  const refresh = useCallback(
    () =>
      api<DriveImport[]>(`/api/events/${eventId}/drive-imports`, { token })
        .then((list) => setLatest(list[0] ?? null))
        .catch(() => {
          // keep the last known state; the next poll tries again
        }),
    [eventId, token],
  );

  useEffect(() => {
    refresh();
  }, [refresh]);

  // while an import runs, poll it and refresh the photo counts above
  useEffect(() => {
    if (!running) return;
    const timer = setInterval(() => {
      refresh();
      onImported();
    }, POLL_MS);
    return () => clearInterval(timer);
  }, [running, refresh, onImported]);

  async function start(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setStarting(true);
    try {
      setLatest(await api<DriveImport>(`/api/events/${eventId}/drive-imports`, { token, json: { link } }));
      setLink("");
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Couldn't start the import.");
    } finally {
      setStarting(false);
    }
  }

  return (
    <div className="mt-4 rounded-xl border px-4 py-4 sm:px-6">
      <div className="flex items-center gap-2">
        <FolderDown className="size-5 text-muted-foreground" aria-hidden="true" />
        <p className="font-medium">Import from Google Drive</p>
      </div>
      <p className="mt-1 text-sm text-muted-foreground">
        Paste a link to a folder shared as &ldquo;Anyone with the link&rdquo;. Subfolders are included, up to 2,000 photos.
      </p>
      <form onSubmit={start} className="mt-3 flex flex-col gap-2 sm:flex-row">
        <Input
          type="url"
          inputMode="url"
          placeholder="https://drive.google.com/drive/folders/..."
          value={link}
          onChange={(e) => setLink(e.target.value)}
          aria-label="Google Drive folder link"
          required
        />
        <Button type="submit" variant="outline" disabled={starting || running || link.trim() === ""}>
          {starting ? <Spinner /> : null} Import
        </Button>
      </form>
      {error && (
        <p className="mt-2 text-sm text-destructive" role="alert">
          {error}
        </p>
      )}

      {latest && (
        <div className="mt-4 rounded-lg bg-muted px-4 py-3" aria-live="polite">
          <div className="flex justify-between gap-3 text-sm">
            <span>{statusWords(latest)}</span>
            {latest.found > 0 && (
              <span className="tabular text-muted-foreground">
                {latest.imported + latest.duplicates + latest.failed}/{latest.found}
              </span>
            )}
          </div>
          {latest.status !== "failed" && <Progress value={driveProgress(latest)} className="mt-2 h-1.5" />}
          <ul className="mt-3 space-y-1 text-sm">
            {latest.status === "failed" && <li className="text-destructive">{driveError(latest.error)}</li>}
            {latest.imported > 0 && <li>{plural(latest.imported, "photo")} imported.</li>}
            {latest.duplicates > 0 && <li className="text-muted-foreground">{latest.duplicates} already in this event, skipped.</li>}
            {latest.skipped > 0 && (
              <li className="text-muted-foreground">
                {latest.skipped} {latest.skipped === 1 ? "file is" : "files are"} not a photo, skipped.
              </li>
            )}
            {latest.overLimit > 0 && (
              <li className="text-muted-foreground">{latest.overLimit} more photos are past the 2,000 limit.</li>
            )}
            {latest.failed > 0 && <li className="text-destructive">{latest.failed} could not be imported:</li>}
            {latest.failures.map((f, i) => (
              <li key={i} className="text-destructive">
                {f.name}: {driveError(f.error)}
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}

function statusWords(i: DriveImport): string {
  switch (i.status) {
    case "listing":
      return "Reading the folder";
    case "importing":
      return "Importing from Drive";
    case "done":
      return i.found === 0 ? "No photos in this folder" : "Import finished";
    default:
      return "Import stopped";
  }
}
