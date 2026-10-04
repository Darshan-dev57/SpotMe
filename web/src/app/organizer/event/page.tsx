"use client";

import { Suspense, useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { ArrowLeft, Send } from "lucide-react";
import { toast } from "sonner";
import { Guests } from "@/components/organizer/guests";
import { PhotoGrid } from "@/components/organizer/photo-grid";
import { Pipeline } from "@/components/organizer/pipeline";
import { Removals } from "@/components/organizer/removals";
import { Settings } from "@/components/organizer/settings";
import { Uploader } from "@/components/organizer/uploader";
import { OrganizerHeader, handleAuthError, useOrganizer } from "@/components/organizer-shell";
import { StatusPill } from "@/components/status-pill";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { ApiError, EventDetail, Photo, api } from "@/lib/api";
import { daysLeft, formatDate, plural } from "@/lib/format";

export default function EventPage() {
  // useSearchParams needs a Suspense boundary in a static export
  return (
    <Suspense>
      <EventDashboard />
    </Suspense>
  );
}

function EventDashboard() {
  const session = useOrganizer();
  const router = useRouter();
  const id = useSearchParams().get("id") ?? "";
  const [event, setEvent] = useState<EventDetail | null>(null);
  const [photos, setPhotos] = useState<Photo[]>([]);
  const [missing, setMissing] = useState(false);

  // bumping `version` re-fetches the event and its photos
  const [version, setVersion] = useState(0);
  const load = useCallback(() => setVersion((v) => v + 1), []);

  useEffect(() => {
    if (!session || !id) return;
    let current = true;
    Promise.all([
      api<EventDetail>(`/api/events/${id}`, { token: session.token }),
      api<Photo[]>(`/api/events/${id}/photos`, { token: session.token }),
    ])
      .then(([e, p]) => {
        if (!current) return;
        setEvent(e);
        setPhotos(p);
      })
      .catch((e) => {
        if (!handleAuthError(e, router) && e instanceof ApiError && e.status === 404) setMissing(true);
      });
    return () => {
      current = false;
    };
  }, [session, id, router, version]);

  // poll while the worker still has photos of this event to process
  const working = !!event && event.counts.queued + event.counts.processing > 0;
  useEffect(() => {
    if (!working) return;
    const t = setInterval(load, 2000);
    return () => clearInterval(t);
  }, [working, load]);

  async function publish() {
    if (!session) return;
    try {
      setEvent(await api<EventDetail>(`/api/events/${id}/publish`, { method: "POST", token: session.token }));
      toast.success("Published. Guests with a link can search now.");
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : "Could not publish.");
    }
  }

  if (!session) return null;

  return (
    <>
      <OrganizerHeader session={session} />
      <main className="mx-auto w-full max-w-6xl px-5 pt-2 pb-20 sm:px-8">
        <Link href="/organizer/events" className="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground">
          <ArrowLeft className="size-4" /> All events
        </Link>

        {missing ? (
          <p className="mt-8 text-muted-foreground">This event doesn&apos;t exist or was deleted.</p>
        ) : !event ? (
          <div className="mt-6 grid gap-4">
            <Skeleton className="h-10 w-2/3" />
            <Skeleton className="h-24" />
          </div>
        ) : (
          <>
            <div className="mt-4 flex flex-wrap items-start justify-between gap-4">
              <div className="min-w-0">
                <h1 className="text-3xl font-semibold sm:text-4xl">{event.title}</h1>
                <p className="mt-2 flex flex-wrap items-center gap-x-3 gap-y-1 text-sm text-muted-foreground">
                  <StatusPill status={event.status} />
                  <span>
                    Deleted on {formatDate(event.expiresAt)} ({plural(daysLeft(event.expiresAt), "day")} left)
                  </span>
                </p>
              </div>
              {event.status === "draft" && (
                <Button size="lg" onClick={publish} disabled={event.counts.ready === 0}>
                  <Send /> Publish to guests
                </Button>
              )}
            </div>

            <div className="mt-8 rounded-xl border bg-card p-4 sm:p-5">
              <Pipeline counts={event.counts} worker={event.worker} />
            </div>

            <Tabs defaultValue="photos" className="mt-10">
              <TabsList className="w-full justify-start overflow-x-auto sm:w-auto">
                <TabsTrigger value="photos">Photos</TabsTrigger>
                <TabsTrigger value="guests">Guests</TabsTrigger>
                <TabsTrigger value="removals">
                  Removals
                  {event.pendingRemovals > 0 && (
                    <span className="tabular ml-1.5 rounded-full bg-primary px-1.5 text-xs text-primary-foreground">
                      {event.pendingRemovals}
                    </span>
                  )}
                </TabsTrigger>
                <TabsTrigger value="settings">Settings</TabsTrigger>
              </TabsList>

              <TabsContent value="photos" className="mt-6">
                <Uploader eventId={event.id} token={session.token} onUploaded={load} />
                <PhotoGrid photos={photos} eventId={event.id} token={session.token} onChanged={load} />
              </TabsContent>
              <TabsContent value="guests" className="mt-6">
                <Guests eventId={event.id} token={session.token} />
              </TabsContent>
              <TabsContent value="removals" className="mt-6">
                <Removals eventId={event.id} token={session.token} onChanged={load} />
              </TabsContent>
              <TabsContent value="settings" className="mt-6">
                <Settings event={event} token={session.token} onChanged={load} />
              </TabsContent>
            </Tabs>
          </>
        )}
      </main>
    </>
  );
}
