"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { SiteHeader } from "@/components/brand";
import { Field } from "@/components/field";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Spinner } from "@/components/ui/spinner";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { ApiError, api } from "@/lib/api";
import { getOrganizer, setOrganizer } from "@/lib/session";

type SessionResponse = { token: string; organizerId: string; name: string };

export default function OrganizerSignIn() {
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (getOrganizer()) router.replace("/organizer/events");
  }, [router]);

  async function submit(kind: "login" | "register", form: FormData) {
    setBusy(true);
    setError(null);
    try {
      const body = Object.fromEntries(form.entries());
      const res = await api<SessionResponse>(`/api/auth/${kind}`, { json: body });
      setOrganizer({ token: res.token, name: res.name });
      router.push("/organizer/events");
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Something went wrong.");
      setBusy(false);
    }
  }

  return (
    <>
      <SiteHeader />
      <main className="mx-auto w-full max-w-md px-5 pt-6 pb-16">
        <h1 className="text-3xl font-semibold">For organizers</h1>

        <Tabs defaultValue="login" className="mt-8" onValueChange={() => setError(null)}>
          <TabsList className="w-full">
            <TabsTrigger value="login">Sign in</TabsTrigger>
            <TabsTrigger value="register">Create account</TabsTrigger>
          </TabsList>

          <TabsContent value="login">
            <form action={(f) => submit("login", f)} className="mt-6 grid gap-5">
              <Field id="login-email" label="Email">
                <Input id="login-email" name="email" type="email" autoComplete="email" required />
              </Field>
              <Field id="login-password" label="Password">
                <Input id="login-password" name="password" type="password" autoComplete="current-password" required />
              </Field>
              {error && <p className="text-sm text-destructive" role="alert">{error}</p>}
              <Button type="submit" size="lg" disabled={busy}>
                {busy && <Spinner />} Sign in
              </Button>
            </form>
          </TabsContent>

          <TabsContent value="register">
            <form action={(f) => submit("register", f)} className="mt-6 grid gap-5">
              <Field id="reg-name" label="Your name" hint="Guests see this on their invite.">
                <Input id="reg-name" name="name" autoComplete="name" required maxLength={60} />
              </Field>
              <Field id="reg-email" label="Email">
                <Input id="reg-email" name="email" type="email" autoComplete="email" required />
              </Field>
              <Field id="reg-password" label="Password" hint="At least 8 characters.">
                <Input id="reg-password" name="password" type="password" autoComplete="new-password" minLength={8} required />
              </Field>
              {error && <p className="text-sm text-destructive" role="alert">{error}</p>}
              <Button type="submit" size="lg" disabled={busy}>
                {busy && <Spinner />} Create account
              </Button>
            </form>
          </TabsContent>
        </Tabs>
      </main>
    </>
  );
}
