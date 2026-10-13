import Link from "next/link";
import { ArrowRight, Camera, Check, ImagePlus, ScanFace } from "lucide-react";
import { SiteHeader } from "@/components/brand";
import { DiscoverButton } from "@/components/obsidian/discover-button";
import { SampleTry } from "@/components/sample-try";
import { Button } from "@/components/ui/button";

/** The contact sheet in the hero: one selfie, and the frames it was found in are marked. */
const SHEET = [
  { src: "/sample/sheet/frame-1.jpg", match: true },
  { src: "/sample/sheet/frame-2.jpg", match: false },
  { src: "/sample/sheet/frame-3.jpg", match: true },
  { src: "/sample/sheet/frame-4.jpg", match: false },
  { src: "/sample/sheet/frame-5.jpg", match: false },
  { src: "/sample/sheet/frame-6.jpg", match: false },
];

const STEPS = [
  {
    icon: ImagePlus,
    title: "The organizer uploads the event photos",
    body: "All of them, from every photographer. Hundreds or thousands is fine.",
  },
  {
    icon: Camera,
    title: "You take one selfie",
    body: "Open the event link on your phone and snap a quick selfie. No app, no account.",
  },
  {
    icon: ScanFace,
    title: "SpotMe finds every photo you are in",
    body: "Face matching picks out your photos in seconds. Download the ones you like.",
  },
];

const FOR = ["Weddings", "College fests", "Hackathons", "Marathons", "Conferences", "Birthday parties"];

const PRIVACY = [
  "Your selfie is used only to find your photos, then deleted. It is never saved.",
  "A search only looks inside the one event you were invited to.",
  "Each guest agrees to the event's notice first, and can ask for photos to be removed.",
];

export default function Home() {
  return (
    <>
      <SiteHeader>
        <Button asChild variant="ghost">
          <Link href="/organizer">For organizers</Link>
        </Button>
      </SiteHeader>

      <main>
        <section className="mx-auto grid max-w-6xl items-center gap-10 px-5 pt-6 pb-14 sm:px-8 lg:grid-cols-[1.05fr_1fr] lg:gap-12 lg:pt-14">
          <div>
            <p className="text-sm font-medium tracking-wide text-primary uppercase">Event photo finder</p>
            <h1 className="mt-3 text-[2.5rem] leading-[1.04] font-semibold sm:text-6xl">
              Find all your photos from any event.
            </h1>
            <p className="mt-5 max-w-[34rem] text-lg leading-relaxed text-muted-foreground">
              Upload one selfie and SpotMe shows you every photo you appear in, out of the hundreds the
              photographer took. No more scrolling through the whole album.
            </p>
            <div className="mt-8 flex flex-wrap items-center gap-x-4 gap-y-3">
              <DiscoverButton label="Try it now" href="#try" className="spotme-discover" />
              <Button asChild size="lg" variant="outline">
                <Link href="/organizer">
                  Create an event <ArrowRight />
                </Link>
              </Button>
            </div>
            <p className="mt-3 text-sm text-muted-foreground">Free to try, no sign-up, works on your phone.</p>
          </div>

          <ContactSheet />
        </section>

        <section id="how" className="border-y bg-card">
          <div className="mx-auto max-w-6xl px-5 py-14 sm:px-8 sm:py-16">
            <h2 className="text-2xl font-semibold sm:text-3xl">How it works</h2>
            <ol className="mt-8 grid gap-8 md:grid-cols-3 md:gap-8">
              {STEPS.map((step, i) => (
                <li key={step.title} className="flex gap-4 md:block">
                  <span className="flex size-12 shrink-0 items-center justify-center rounded-full bg-primary text-primary-foreground">
                    <step.icon className="size-6" aria-hidden="true" />
                  </span>
                  <div className="md:mt-4">
                    <span className="tabular text-sm text-muted-foreground">Step {i + 1}</span>
                    <h3 className="mt-1 text-lg leading-snug font-semibold">{step.title}</h3>
                    <p className="mt-1.5 leading-relaxed text-muted-foreground">{step.body}</p>
                  </div>
                </li>
              ))}
            </ol>
          </div>
        </section>

        <section id="try" className="mx-auto max-w-6xl scroll-mt-4 px-5 py-14 sm:px-8 sm:py-16">
          <h2 className="text-2xl font-semibold sm:text-3xl">Try it with a sample event</h2>
          <p className="mt-3 max-w-2xl leading-relaxed text-muted-foreground">
            This sample album has 37 photos from NASA&apos;s Crew-1 space mission: four astronauts, in group
            shots and on their own, mixed with photos of other people and the rocket. Tap a face and SpotMe
            will search the album for that person, just like it searches for you at a real event.
          </p>
          <SampleTry />
        </section>

        <section className="border-y bg-card">
          <div className="mx-auto grid max-w-6xl gap-8 px-5 py-14 sm:px-8 lg:grid-cols-2 lg:gap-12">
            <div>
              <h2 className="text-2xl font-semibold sm:text-3xl">Made for any event with a photographer</h2>
              <p className="mt-3 max-w-md leading-relaxed text-muted-foreground">
                Photographers shoot hundreds of photos. Guests only want the ones they are in.
              </p>
              <ul className="mt-6 flex flex-wrap gap-2">
                {FOR.map((f) => (
                  <li key={f} className="rounded-full border bg-background px-4 py-2 text-sm font-medium">
                    {f}
                  </li>
                ))}
              </ul>
            </div>
            <div>
              <h2 className="text-2xl font-semibold sm:text-3xl">Private by design</h2>
              <ul className="mt-5 divide-y">
                {PRIVACY.map((p) => (
                  <li key={p} className="flex gap-3 py-3.5 first:pt-0">
                    <Check className="mt-1 size-4 shrink-0 text-primary" aria-hidden="true" />
                    <span className="leading-relaxed">{p}</span>
                  </li>
                ))}
              </ul>
            </div>
          </div>
        </section>

        <section className="mx-auto max-w-6xl px-5 py-14 text-center sm:px-8">
          <h2 className="text-2xl font-semibold sm:text-3xl">Have an event coming up?</h2>
          <p className="mx-auto mt-3 max-w-md leading-relaxed text-muted-foreground">
            Create an event, upload the photos and share one link with your guests.
          </p>
          <Button asChild size="lg" className="mt-6">
            <Link href="/organizer">
              Create an event <ArrowRight />
            </Link>
          </Button>
        </section>
      </main>

      <footer className="border-t">
        <div className="mx-auto flex max-w-6xl flex-wrap items-center justify-between gap-3 px-5 py-6 text-sm text-muted-foreground sm:px-8">
          <span className="font-medium text-foreground">SpotMe</span>
          <Link href="/organizer" className="underline-offset-4 hover:underline">
            Organizer sign in
          </Link>
        </div>
      </footer>
    </>
  );
}

/** A strip of frames like a photo lab's contact sheet: the selfie, and the frames it was found in. */
function ContactSheet() {
  return (
    <figure className="relative mx-auto w-full max-w-lg pb-6">
      <div className="rounded-xl bg-foreground p-3 pb-2 shadow-[0_24px_60px_-28px_oklch(0.21_0.015_60/0.6)] sm:p-4 sm:pb-3">
        <div className="grid grid-cols-3 gap-2 sm:gap-3">
          {SHEET.map((frame, i) => (
            <div key={frame.src} className={i === 0 ? "col-span-2 row-span-2" : ""}>
              <div
                className={
                  "relative h-full overflow-hidden rounded-md " +
                  (frame.match ? "ring-[3px] ring-primary" : "opacity-50 grayscale")
                }
              >
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img src={frame.src} alt="" className="aspect-square h-full w-full object-cover" />
                {frame.match && (
                  <span className="absolute top-1.5 left-1.5 flex size-5 items-center justify-center rounded-full bg-primary text-primary-foreground">
                    <Check className="size-3.5" aria-hidden="true" />
                  </span>
                )}
              </div>
            </div>
          ))}
        </div>
      </div>
      <figcaption className="absolute -bottom-1 left-3 flex items-center gap-2 rounded-full bg-card py-1 pr-4 pl-1 text-sm font-medium shadow-md ring-1 ring-border sm:left-4">
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img src="/sample/faces/walker.jpg" alt="" className="size-9 rounded-full object-cover" />
        One selfie, 2 of 6 photos found
      </figcaption>
    </figure>
  );
}
