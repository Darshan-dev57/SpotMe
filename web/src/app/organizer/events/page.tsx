"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { CalendarClock, ChevronRight, Plus } from "lucide-react";
import { Field } from "@/components/field";
import { OrganizerHeader, handleAuthError, useOrganizer } from "@/components/organizer-shell";
import { StatusPill } from "@/components/status-pill";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { Textarea } from "@/components/ui/textarea";
import { ApiError, EventSummary, api } from "@/lib/api";
import { daysLeft, formatDate, plural } from "@/lib/format";

const DEFAULT_NOTICE =
  "SpotMe compares one selfie with the photos from this event so you can find the ones you are in. " +
  "Your selfie is not saved. Face data is used only for this event and deleted when the event expires. " +
  "Group photos may also show other guests who agreed.";

function inDays(n: number) {
  const d = new Date(Date.now() + n * 86_400_000);
  return d.toISOString().slice(0, 10);
}

export default function EventsPage() {
  const session = useOrganizer();
  const router = useRouter();
  const [events, setEvents] = useState<EventSummary[] | null>(null);
  const [creating, setCreating] = useState(false);

  useEffect(() => {
    if (!session) return;
    api<EventSummary[]>("/api/events", { token: session.token })
      .then(setEvents)
      .catch((e) => handleAuthError(e, router));
  }, [session, router]);

  if (!session) return null;

  return (
    <>
      <OrganizerHeader session={session} />
      <main className="mx-auto w-full max-w-3xl px-5 pt-4 pb-16 sm:px-8">
        <div className="flex items-end justify-between gap-4">
          <h1 className="text-3xl font-semibold">Your events</h1>
          {!creating && events && events.length > 0 && (
            <Button onClick={() => setCreating(true)}>
              <Plus /> New event
            </Button>
          )}
        </div>

        {(creating || (events && events.length === 0)) && (
          <CreateEvent
            token={session.token}
            canCancel={!!events && events.length > 0}
            onCancel={() => setCreating(false)}
            onCreated={(id) => router.push(`/organizer/event?id=${id}`)}
          />
        )}

        {events === null ? (
          <div className="mt-8 grid gap-3">
            <Skeleton className="h-20" />
            <Skeleton className="h-20" />
          </div>
        ) : events.length === 0 ? null : (
          <ul className="mt-8 divide-y rounded-xl border bg-card">
            {events.map((e) => (
              <li key={e.id}>
                <Link
                  href={`/organizer/event?id=${e.id}`}
                  className="flex items-center gap-4 px-4 py-4 transition-colors hover:bg-accent/60 sm:px-5"
                >
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-2">
                      <span className="truncate font-medium">{e.title}</span>
                      <StatusPill status={e.status} />
                    </div>
                    <p className="mt-1 text-sm text-muted-foreground">
                      <span className="tabular">{e.readyCount}</span> of {plural(e.photoCount, "photo")} ready ·
                      expires {formatDate(e.expiresAt)}
                    </p>
                  </div>
                  <ChevronRight className="size-4 text-muted-foreground" />
                </Link>
              </li>
            ))}
          </ul>
        )}
      </main>
    </>
  );
}

function CreateEvent({
  token,
  canCancel,
  onCancel,
  onCreated,
}: {
  token: string;
  canCancel: boolean;
  onCancel: () => void;
  onCreated: (id: string) => void;
}) {
  const [agreed, setAgreed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [expires, setExpires] = useState(inDays(30));

  async function submit(form: FormData) {
    setBusy(true);
    setError(null);
    try {
      const res = await api<{ id: string }>("/api/events", {
        token,
        json: {
          title: form.get("title"),
          purposeNotice: form.get("notice"),
          // end of the chosen day in the organizer's time zone
          expiresAt: new Date(`${expires}T23:59:00`).toISOString(),
          consentAttested: agreed,
        },
      });
      onCreated(res.id);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Could not create the event.");
      setBusy(false);
    }
  }

  return (
    <section className="mt-8 rounded-xl border bg-card p-5 sm:p-6">
      <h2 className="text-xl font-semibold">New private event</h2>
      <p className="mt-1 text-sm text-muted-foreground">Guests can only search after you publish it.</p>
      <form action={submit} className="mt-6 grid gap-5">
        <Field id="title" label="Event name">
          <Input id="title" name="title" required maxLength={80} placeholder="Robotics club farewell" />
        </Field>
        <Field id="notice" label="What guests read before searching" hint="Say who you are, why, and when it is deleted.">
          <Textarea id="notice" name="notice" required maxLength={1000} rows={4} defaultValue={DEFAULT_NOTICE} />
        </Field>
        <Field id="expires" label="Delete everything on" hint="Up to 90 days from today.">
          <div className="relative">
            <CalendarClock className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted-foreground" />
            <Input
              id="expires"
              type="date"
              className="pl-9"
              value={expires}
              min={inDays(1)}
              max={inDays(90)}
              onChange={(e) => setExpires(e.target.value)}
              required
            />
          </div>
          <span className="sr-only">{daysLeft(`${expires}T23:59:00`)} days</span>
        </Field>
        <label className="flex items-start gap-3 rounded-lg bg-muted p-4 text-sm leading-relaxed">
          <Checkbox checked={agreed} onCheckedChange={(v) => setAgreed(v === true)} className="mt-0.5" />
          <span>
            Everyone in these photos is an adult and has agreed to their face being matched for this event. I will
            leave out photos of anyone who has not.
          </span>
        </label>
        {error && <p className="text-sm text-destructive" role="alert">{error}</p>}
        <div className="flex flex-wrap gap-3">
          <Button type="submit" size="lg" disabled={!agreed || busy}>
            {busy && <Spinner />} Create event
          </Button>
          {canCancel && (
            <Button type="button" variant="ghost" size="lg" onClick={onCancel}>
              Cancel
            </Button>
          )}
        </div>
      </form>
    </section>
  );
}
