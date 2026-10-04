"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { Sparkles } from "lucide-react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";
import { ApiError, api } from "@/lib/api";

type DemoSummary = { title: string; photoCount: number; readyCount: number };

/**
 * "Try the demo": asks the API for a personal invite to the public demo event and opens it.
 * Renders nothing when the server has no demo (local development, most self-hosted setups).
 */
export function DemoButton() {
  const router = useRouter();
  const [demo, setDemo] = useState<DemoSummary | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    api<DemoSummary>("/api/demo")
      .then(setDemo)
      .catch(() => setDemo(null));
  }, []);

  if (!demo) return null;

  async function open() {
    setBusy(true);
    try {
      const { token } = await api<{ token: string }>("/api/demo/invite", { method: "POST" });
      router.push(`/join#${token}`);
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : "Could not open the demo.");
      setBusy(false);
    }
  }

  return (
    <div className="flex flex-col items-start gap-1.5">
      <Button size="lg" onClick={open} disabled={busy}>
        {busy ? <Spinner /> : <Sparkles />} Try the demo
      </Button>
      <span className="tabular text-xs text-muted-foreground">
        {demo.photoCount} public domain photos · no sign up
      </span>
    </div>
  );
}
