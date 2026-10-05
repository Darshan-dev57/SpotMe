"use client";

import { useState } from "react";
import { Check } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";
import { ApiError, Notice, api } from "@/lib/api";
import { formatDate } from "@/lib/format";

/** What the guest reads and agrees to before any selfie is asked for. */
export function NoticeStep({
  notice,
  invite,
  onAgreed,
  onDeclined,
}: {
  notice: Notice;
  invite: string;
  onAgreed: (sessionToken: string) => void;
  onDeclined: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function agree() {
    setBusy(true);
    setError(null);
    try {
      const res = await api<{ sessionToken: string }>(`/api/join/${encodeURIComponent(invite)}/consent`, {
        json: { noticeVersion: notice.noticeVersion, agree: true },
      });
      onAgreed(res.sessionToken);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Something went wrong.");
      setBusy(false);
    }
  }

  const facts = [
    "Your selfie is compared in memory and never saved.",
    `Only photos from ${notice.eventTitle} are searched.`,
    "Group photos can also show other guests who agreed.",
    "Results are likely matches. They don't prove who anyone is.",
    `Everything is deleted on ${formatDate(notice.expiresAt)}.`,
  ];

  return (
    <article className="pt-6">
      <p className="text-sm text-muted-foreground">
        {notice.demo ? "Public demo · anyone can try it" : `${notice.organizer} invited ${notice.invitedAs}`}
      </p>
      <h1 className="mt-1 text-3xl font-semibold sm:text-4xl">{notice.eventTitle}</h1>
      <p className="mt-4 max-w-prose text-lg leading-relaxed">Find the photos you are in with one selfie.</p>

      <section className="mt-8 rounded-xl border bg-card p-5 sm:p-6">
        <h2 className="font-medium">From the organizer</h2>
        <p className="mt-2 leading-relaxed whitespace-pre-line text-muted-foreground">{notice.purposeNotice}</p>
        <ul className="mt-5 grid gap-2.5 border-t pt-5">
          {facts.map((f) => (
            <li key={f} className="flex gap-2.5 text-sm leading-relaxed">
              <Check className="mt-0.5 size-4 shrink-0 text-primary" aria-hidden="true" />
              {f}
            </li>
          ))}
        </ul>
      </section>

      {error && <p className="mt-4 text-sm text-destructive" role="alert">{error}</p>}
      <div className="mt-6 flex flex-wrap gap-3">
        <Button size="lg" onClick={agree} disabled={busy}>
          {busy && <Spinner />} I agree, continue
        </Button>
        <Button size="lg" variant="ghost" onClick={onDeclined}>
          No thanks
        </Button>
      </div>
    </article>
  );
}
