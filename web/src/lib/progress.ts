import { useEffect, useState } from "react";
import { Counts, api, mediaUrl } from "@/lib/api";

export type Progress = {
  counts: Counts;
  faces: number;
  hidden: number;
  worker: { active: boolean; lastSeen: string | null };
};

export type ProgressMode = "connecting" | "live" | "polling";

/** Splits a Server-Sent Events buffer into the data of each complete event and the unfinished rest. */
export function parseSse(buffer: string): { data: string[]; rest: string } {
  const parts = buffer.split(/\r?\n\r?\n/);
  const rest = parts.pop() ?? "";
  const data: string[] = [];
  for (const part of parts) {
    const lines = part
      .split(/\r?\n/)
      .filter((line) => line.startsWith("data:"))
      .map((line) => line.slice(5).replace(/^ /, ""));
    // comment lines (": keep-alive") carry no data and are skipped
    if (lines.length > 0) data.push(lines.join("\n"));
  }
  return { data, rest };
}

/**
 * Live indexing counts for one event. Reads the server's event stream with fetch, so the
 * organizer's token stays in a header (EventSource can't send one). If the stream can't be
 * opened, sends nothing, or closes at once (a proxy that buffers), it polls every 3 seconds instead.
 */
export function useLiveProgress(eventId: string, token: string | undefined) {
  const [progress, setProgress] = useState<Progress | null>(null);
  const [mode, setMode] = useState<ProgressMode>("connecting");

  useEffect(() => {
    if (!eventId || !token) return;
    const abort = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;

    async function poll() {
      try {
        setProgress(await api<Progress>(`/api/events/${eventId}/progress`, { token }));
      } catch {
        // keep the last numbers on screen and try again
      }
      if (!abort.signal.aborted) timer = setTimeout(poll, 3000);
    }

    async function stream() {
      for (;;) {
        const started = Date.now();
        const res = await fetch(mediaUrl(`/api/events/${eventId}/progress/stream`), {
          headers: { Authorization: `Bearer ${token}`, Accept: "text/event-stream" },
          signal: abort.signal,
        });
        if (!res.ok || !res.body) throw new Error(`stream refused (${res.status})`);
        const reader = res.body.getReader();
        const decoder = new TextDecoder();
        let buffer = "";
        // the server sends the current counts at once; nothing after 8 seconds means a proxy holds them back
        let gotData = false;
        const watchdog = setTimeout(() => {
          if (!gotData) reader.cancel();
        }, 8000);
        try {
          for (;;) {
            const { value, done } = await reader.read();
            if (done) break;
            buffer += decoder.decode(value, { stream: true });
            const parsed = parseSse(buffer);
            buffer = parsed.rest;
            for (const data of parsed.data) {
              gotData = true;
              setMode("live");
              setProgress(JSON.parse(data) as Progress);
            }
          }
        } finally {
          clearTimeout(watchdog);
        }
        if (!gotData) throw new Error("no events arrived");
        // the server ends each stream after 10 minutes; one that ends at once means a proxy is in the way
        if (Date.now() - started < 5000) throw new Error("stream closed at once");
      }
    }

    stream().catch(() => {
      if (abort.signal.aborted) return;
      setMode("polling");
      poll();
    });

    return () => {
      abort.abort();
      clearTimeout(timer);
    };
  }, [eventId, token]);

  return { progress, mode };
}
