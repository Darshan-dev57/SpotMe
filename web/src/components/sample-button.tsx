"use client";

import { useEffect, useState } from "react";
import { toast } from "sonner";
import { DiscoverButton } from "@/components/obsidian/discover-button";
import { Spinner } from "@/components/ui/spinner";
import { ApiError, api } from "@/lib/api";

type SampleSummary = { title: string; photoCount: number; readyCount: number };

/**
 * "Try it with sample photos": asks the API for a personal invite to the public sample event and opens it.
 * Renders nothing when the server has no sample event (local development, most self-hosted setups).
 */
export function SampleButton() {
  const [sample, setSample] = useState<SampleSummary | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    api<SampleSummary>("/api/sample")
      .then(setSample)
      .catch(() => setSample(null));
  }, []);

  if (!sample) return null;

  async function open() {
    setBusy(true);
    try {
      const { token } = await api<{ token: string }>("/api/sample/invite", { method: "POST" });
      // a full page load, so the join page sees the token in the "#" from its first render
      // (a client-side push showed "This invite can't be opened" for a moment first)
      window.location.assign(`/join#${token}`);
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : "Could not open the sample event.");
      setBusy(false);
    }
  }

  return (
    <div className="flex flex-col items-start gap-2">
      <DiscoverButton
        label="Try it with sample photos"
        onClick={open}
        disabled={busy}
        icon={busy ? <Spinner className="size-5" /> : undefined}
        className="spotme-discover"
      />
      <span className="tabular text-xs text-muted-foreground">
        {sample.photoCount} photos · no sign-up
      </span>
    </div>
  );
}
