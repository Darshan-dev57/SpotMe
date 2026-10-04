import Link from "next/link";
import { SiteHeader } from "@/components/brand";
import { Button } from "@/components/ui/button";

export default function NotFound() {
  return (
    <>
      <SiteHeader />
      <main className="mx-auto w-full max-w-md px-5 pt-16 pb-16">
        <p className="tabular text-sm text-muted-foreground">404</p>
        <h1 className="mt-2 text-3xl font-semibold">This page isn&apos;t here</h1>
        <p className="mt-3 leading-relaxed text-muted-foreground">
          If you followed an invite link, ask the organizer for a fresh one. Links stop working when an event expires
          or your access is removed.
        </p>
        <Button asChild className="mt-8">
          <Link href="/">Go to the home page</Link>
        </Button>
      </main>
    </>
  );
}
