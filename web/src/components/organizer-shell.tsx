"use client";

import { useEffect, useMemo } from "react";
import { useRouter } from "next/navigation";
import { LogOut } from "lucide-react";
import { SiteHeader } from "@/components/brand";
import { Button } from "@/components/ui/button";
import { api } from "@/lib/api";
import { useLocalStorage } from "@/lib/hooks";
import { ORGANIZER_KEY, OrganizerSession, setOrganizer } from "@/lib/session";

/** Header + sign-out for organizer pages. Sends you to sign in when there is no session. */
export function useOrganizer(): OrganizerSession | null {
  const router = useRouter();
  const raw = useLocalStorage(ORGANIZER_KEY);
  useEffect(() => {
    if (raw === null) router.replace("/organizer");
  }, [raw, router]);
  return useMemo(() => (raw ? (JSON.parse(raw) as OrganizerSession) : null), [raw]);
}

export function OrganizerHeader({ session }: { session: OrganizerSession }) {
  const router = useRouter();
  async function signOut() {
    await api("/api/auth/logout", { method: "POST", token: session.token }).catch(() => undefined);
    setOrganizer(null);
    router.replace("/organizer");
  }
  return (
    <SiteHeader>
      <span className="hidden text-sm text-muted-foreground sm:inline">{session.name}</span>
      <Button variant="ghost" size="sm" onClick={signOut}>
        <LogOut /> Sign out
      </Button>
    </SiteHeader>
  );
}

/** Any 401 from the API means the session is gone: forget it and go to sign in. */
export function handleAuthError(e: unknown, router: ReturnType<typeof useRouter>): boolean {
  if (e && typeof e === "object" && "status" in e && (e as { status: number }).status === 401) {
    setOrganizer(null);
    router.replace("/organizer");
    return true;
  }
  return false;
}
