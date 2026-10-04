import Link from "next/link";
import { cn } from "@/lib/utils";

/** Viewfinder corners around a dot: "this one is you". */
export function BrandMark({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 32 32" aria-hidden="true" className={cn("size-7", className)}>
      <g fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round">
        <path d="M4 11V6.5A2.5 2.5 0 0 1 6.5 4H11" />
        <path d="M21 4h4.5A2.5 2.5 0 0 1 28 6.5V11" />
        <path d="M28 21v4.5a2.5 2.5 0 0 1-2.5 2.5H21" />
        <path d="M11 28H6.5A2.5 2.5 0 0 1 4 25.5V21" />
      </g>
      <circle cx="16" cy="16" r="4.5" className="fill-primary" />
    </svg>
  );
}

export function SiteHeader({ children }: { children?: React.ReactNode }) {
  return (
    <header className="mx-auto flex w-full max-w-6xl items-center justify-between gap-4 px-5 py-4 sm:px-8">
      <Link href="/" className="flex items-center gap-2 rounded-md font-semibold tracking-tight">
        <BrandMark />
        <span className="text-lg">SpotMe</span>
      </Link>
      <div className="flex items-center gap-2">{children}</div>
    </header>
  );
}
