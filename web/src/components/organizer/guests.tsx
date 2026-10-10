"use client";

import { useEffect, useState } from "react";
import QRCode from "qrcode";
import { Check, Copy, QrCode, UserMinus } from "lucide-react";
import { toast } from "sonner";
import { Field } from "@/components/field";
import { Button } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";
import { Textarea } from "@/components/ui/textarea";
import { ApiError, Invite, api } from "@/lib/api";

type NewInvite = { id: string; label: string; joinUrl: string };

/** Personal invite links. Each guest gets their own, so access and withdrawal are per person. */
export function Guests({ eventId, token }: { eventId: string; token: string }) {
  const [invites, setInvites] = useState<Invite[]>([]);
  const [fresh, setFresh] = useState<NewInvite[]>([]);
  const [busy, setBusy] = useState(false);

  const [version, setVersion] = useState(0);
  const load = () => setVersion((v) => v + 1);

  useEffect(() => {
    api<Invite[]>(`/api/events/${eventId}/invites`, { token })
      .then(setInvites)
      .catch(() => undefined);
  }, [eventId, token, version]);

  async function create(form: FormData) {
    const names = String(form.get("names") ?? "")
      .split("\n")
      .map((n) => n.trim())
      .filter(Boolean)
      .slice(0, 100);
    if (names.length === 0) return;
    setBusy(true);
    const made: NewInvite[] = [];
    try {
      for (const label of names) {
        made.push(await api<NewInvite>(`/api/events/${eventId}/invites`, { token, json: { label } }));
      }
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : "Could not create every invite.");
    }
    setFresh((prev) => [...made, ...prev]);
    setBusy(false);
    load();
  }

  async function revoke(id: string) {
    await api(`/api/events/${eventId}/invites/${id}`, { method: "DELETE", token });
    toast.success("Access removed.");
    load();
  }

  return (
    <div className="grid gap-8 lg:grid-cols-[1fr_1.2fr]">
      <form action={create} className="grid content-start gap-4">
        <Field id="names" label="Invite guests" hint="One name per line. Each person gets their own private link.">
          <Textarea id="names" name="names" rows={5} placeholder={"Asha\nRohan\nMeera"} />
        </Field>
        <Button type="submit" disabled={busy} className="justify-self-start">
          {busy && <Spinner />} Create links
        </Button>
      </form>

      <div>
        {fresh.length > 0 && (
          <div className="mb-8">
            <h3 className="font-medium">New links</h3>
            <p className="mt-1 text-sm text-muted-foreground">
              Copy them now. For privacy, a link is only shown once; make a new one if it gets lost.
            </p>
            <ul className="mt-3 divide-y rounded-lg border bg-card">
              {fresh.map((inv) => (
                <FreshInvite key={inv.id} invite={inv} />
              ))}
            </ul>
          </div>
        )}

        <h3 className="font-medium">Guests</h3>
        {invites.length === 0 ? (
          <p className="mt-2 text-sm text-muted-foreground">No one invited yet.</p>
        ) : (
          <ul className="mt-3 divide-y rounded-lg border bg-card">
            {invites.map((inv) => (
              <li key={inv.id} className="flex items-center justify-between gap-3 px-4 py-3">
                <div className="min-w-0">
                  <p className="truncate">{inv.label}</p>
                  <p className="text-sm text-muted-foreground">{inviteState(inv)}</p>
                </div>
                {!inv.revokedAt && !inv.withdrawnAt && (
                  <Button variant="ghost" size="sm" onClick={() => revoke(inv.id)}>
                    <UserMinus /> Remove
                  </Button>
                )}
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}

function inviteState(inv: Invite) {
  if (inv.revokedAt) return "Access removed";
  if (inv.withdrawnAt) return "Withdrew consent";
  if (inv.consentedAt) return "Agreed and joined";
  return "Invited, not opened yet";
}

function FreshInvite({ invite }: { invite: NewInvite }) {
  const [copied, setCopied] = useState(false);
  const [qr, setQr] = useState<string | null>(null);

  async function copy() {
    await navigator.clipboard.writeText(invite.joinUrl);
    setCopied(true);
    setTimeout(() => setCopied(false), 1500);
  }

  async function toggleQr() {
    setQr(qr ? null : await QRCode.toDataURL(invite.joinUrl, { margin: 1, width: 240 }));
  }

  return (
    <li className="px-4 py-3">
      <div className="flex items-center justify-between gap-2">
        <span className="truncate font-medium">{invite.label}</span>
        <div className="flex shrink-0 gap-1">
          <Button variant="ghost" size="sm" onClick={toggleQr} aria-label={`QR code for ${invite.label}`}>
            <QrCode />
          </Button>
          <Button variant="outline" size="sm" onClick={copy}>
            {copied ? <Check /> : <Copy />} {copied ? "Copied" : "Copy link"}
          </Button>
        </div>
      </div>
      {qr && (
        // eslint-disable-next-line @next/next/no-img-element
        <img src={qr} alt={`QR code that opens ${invite.label}'s invite`} className="mt-3 size-40 rounded-md border" />
      )}
    </li>
  );
}
