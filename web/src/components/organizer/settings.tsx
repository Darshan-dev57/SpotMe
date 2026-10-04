"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { toast } from "sonner";
import { Field } from "@/components/field";
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import { Textarea } from "@/components/ui/textarea";
import { ApiError, EventDetail, api } from "@/lib/api";
import { formatDate, plural } from "@/lib/format";

export function Settings({ event, token, onChanged }: { event: EventDetail; token: string; onChanged: () => void }) {
  const router = useRouter();
  const [notice, setNotice] = useState(event.purposeNotice);
  const [confirming, setConfirming] = useState(false);
  const total = event.counts.ready + event.counts.queued + event.counts.processing + event.counts.failed;

  async function saveNotice() {
    try {
      await api(`/api/events/${event.id}/notice`, { token, json: { purposeNotice: notice } });
      toast.success("Notice updated. Guests will be asked to agree again.");
      onChanged();
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : "Could not save.");
    }
  }

  async function deleteEvent() {
    await api(`/api/events/${event.id}`, { method: "DELETE", token });
    toast.success("Deleting the event. Files and face data are being removed.");
    router.push("/organizer/events");
  }

  return (
    <div className="grid max-w-2xl gap-10">
      <section className="grid gap-4">
        <Field
          id="notice-edit"
          label="Notice guests agree to"
          hint={`Version ${event.noticeVersion}. Saving a change asks every guest to read and agree again.`}
        >
          <Textarea id="notice-edit" rows={5} value={notice} maxLength={1000} onChange={(e) => setNotice(e.target.value)} />
        </Field>
        <Button
          variant="outline"
          className="justify-self-start"
          disabled={notice.trim() === event.purposeNotice || notice.trim().length === 0}
          onClick={saveNotice}
        >
          Save notice
        </Button>
      </section>

      <Separator />

      <section>
        <h3 className="font-medium">Delete this event</h3>
        <p className="mt-1 max-w-prose text-sm leading-relaxed text-muted-foreground">
          It is deleted automatically on {formatDate(event.expiresAt)}. Deleting now removes {plural(total, "photo")},
          their thumbnails, every face vector and all guest links. Photos guests already downloaded stay on their
          phones.
        </p>
        {confirming ? (
          <div className="mt-4 flex flex-wrap gap-2">
            <Button variant="destructive" onClick={deleteEvent}>
              Yes, delete everything
            </Button>
            <Button variant="ghost" onClick={() => setConfirming(false)}>
              Cancel
            </Button>
          </div>
        ) : (
          <Button variant="outline" className="mt-4 text-destructive" onClick={() => setConfirming(true)}>
            Delete event
          </Button>
        )}
      </section>
    </div>
  );
}
