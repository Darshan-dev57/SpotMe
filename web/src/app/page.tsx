import Link from "next/link";
import { ArrowRight, Check } from "lucide-react";
import { SiteHeader } from "@/components/brand";
import { SampleButton } from "@/components/sample-button";
import { Button } from "@/components/ui/button";

const SHEET = [
  { src: "/sample/behnken.jpg", match: false },
  { src: "/sample/crew.jpg", match: true },
  { src: "/sample/glover.jpg", match: false },
  { src: "/sample/hurley-2.jpg", match: true },
  { src: "/sample/hopkins.jpg", match: false },
];

const STEPS = [
  { title: "The organizer uploads the album", body: "Faces are found in the background." },
  { title: "You open your invite and take one selfie", body: "It is matched in memory, then thrown away." },
  { title: "You get the photos you are likely in", body: "Only from that event. Download or remove them." },
];

const PROMISES = [
  "Your selfie is never saved.",
  "A search only looks inside the event you were invited to.",
  "Everything is deleted when the event expires, at most 90 days out.",
  "Photos you ask to remove are hidden at once.",
];

export default function Home() {
  return (
    <>
      <SiteHeader>
        <Button asChild variant="ghost">
          <Link href="/organizer">Organizer sign in</Link>
        </Button>
      </SiteHeader>

      <main>
        <section className="mx-auto grid max-w-6xl items-center gap-12 px-5 pt-8 pb-16 sm:px-8 lg:grid-cols-[1.05fr_1fr] lg:pt-16">
          <div>
            <h1 className="text-[2.6rem] leading-[1.02] font-semibold sm:text-6xl">
              Find yourself in the event album.
            </h1>
            <p className="mt-5 max-w-[34rem] text-lg leading-relaxed text-muted-foreground">
              One selfie shows the photos you are probably in, only from the event you were invited to.
            </p>
            <div className="mt-8 flex flex-wrap items-start gap-x-4 gap-y-3">
              <SampleButton />
              <Button asChild size="lg" variant="outline">
                <Link href="/organizer">
                  Create an event <ArrowRight />
                </Link>
              </Button>
            </div>
          </div>

          <ContactSheet />
        </section>

        <section className="border-y bg-card">
          <div className="mx-auto max-w-6xl px-5 py-16 sm:px-8">
            <h2 className="text-2xl font-semibold sm:text-3xl">How it works</h2>
            <ol className="mt-10 grid gap-10 md:grid-cols-3 md:gap-8">
              {STEPS.map((step, i) => (
                <li key={step.title} className="border-t border-foreground/80 pt-4">
                  <span className="tabular text-sm text-muted-foreground">Step {i + 1}</span>
                  <h3 className="mt-2 text-lg font-semibold">{step.title}</h3>
                  <p className="mt-2 leading-relaxed text-muted-foreground">{step.body}</p>
                </li>
              ))}
            </ol>
          </div>
        </section>

        <section className="mx-auto grid max-w-6xl gap-10 px-5 py-16 sm:px-8 lg:grid-cols-[1fr_1.4fr]">
          <div>
            <h2 className="text-2xl font-semibold sm:text-3xl">Built around consent</h2>
            <p className="mt-4 max-w-md leading-relaxed text-muted-foreground">
              Every guest reads and agrees to the event&apos;s notice before searching.
            </p>
          </div>
          <ul className="divide-y">
            {PROMISES.map((p) => (
              <li key={p} className="flex gap-3 py-4 first:pt-0">
                <Check className="mt-1 size-4 shrink-0 text-primary" aria-hidden="true" />
                <span className="leading-relaxed">{p}</span>
              </li>
            ))}
          </ul>
        </section>
      </main>

      <footer className="border-t">
        <div className="mx-auto flex max-w-6xl flex-col gap-2 px-5 py-8 text-sm text-muted-foreground sm:flex-row sm:justify-between sm:px-8">
          <p>A portfolio project.</p>
          <p>
            Sample photos: NASA · UI parts from{" "}
            <a className="underline" href="https://www.obsidianui.dev/">
              ObsidianUI
            </a>
          </p>
        </div>
      </footer>
    </>
  );
}

/** A strip of frames like a photo lab's contact sheet, with the matches marked. */
function ContactSheet() {
  return (
    <figure className="relative mx-auto w-full max-w-lg">
      <div className="rounded-xl bg-foreground p-3 pb-2 shadow-[0_24px_60px_-28px_oklch(0.21_0.015_60/0.6)] sm:p-4 sm:pb-3">
        <div className="grid grid-cols-3 gap-2 sm:gap-3">
          {SHEET.map((frame, i) => (
            <div key={frame.src} className={i === 1 ? "col-span-2 row-span-2" : ""}>
              <div
                className={
                  "relative h-full overflow-hidden rounded-md " +
                  (frame.match ? "ring-[3px] ring-primary" : "opacity-60 grayscale")
                }
              >
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img src={frame.src} alt="" className="aspect-square h-full w-full object-cover" />
              </div>
              <span className="tabular mt-1 block text-[10px] text-background/60">
                {String(i + 12).padStart(3, "0")}
              </span>
            </div>
          ))}
        </div>
      </div>
    </figure>
  );
}
