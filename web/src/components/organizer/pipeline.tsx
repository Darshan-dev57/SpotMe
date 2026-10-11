import { Counts } from "@/lib/api";
import { plural } from "@/lib/format";
import { ProgressMode } from "@/lib/progress";
import { cn } from "@/lib/utils";

/**
 * Where the photos are in the pipeline: one bar split into ready / processing / queued / failed.
 * "Uploaded" is not "searchable", so the organizer sees both. Empty parts and an idle worker are not shown.
 */
export function Pipeline({
  counts,
  hidden,
  worker,
  live,
}: {
  counts: Counts;
  /** ready photos that are hidden (a removal request or the organizer), so not searchable */
  hidden: number;
  worker: { active: boolean; lastSeen: string | null };
  /** "live": numbers arrive over the event stream; "polling": refreshed every few seconds */
  live?: ProgressMode;
}) {
  const total = counts.ready + counts.processing + counts.queued + counts.failed;
  const parts = [
    { key: "ready", label: "ready", value: counts.ready, className: "bg-ok" },
    { key: "processing", label: "processing", value: counts.processing, className: "bg-busy" },
    { key: "queued", label: "queued", value: counts.queued, className: "bg-input" },
    { key: "failed", label: "failed", value: counts.failed, className: "bg-destructive" },
  ];
  const working = counts.queued + counts.processing > 0;

  return (
    <div>
      <div className="flex flex-wrap items-baseline justify-between gap-x-6 gap-y-1">
        <p className="text-sm">
          <span className="tabular text-base font-medium">{counts.ready - hidden}</span>
          <span className="text-muted-foreground"> of {plural(total, "photo")} searchable</span>
          {hidden > 0 && <span className="text-muted-foreground"> · {hidden} hidden</span>}
        </p>
        {live === "live" && (
          <p className="flex items-center gap-1.5 text-xs text-muted-foreground" title="Counts update by themselves">
            <span aria-hidden="true" className="size-1.5 animate-pulse rounded-full bg-ok" /> Live
          </p>
        )}
        {(working || !worker.active) && (
          <p className="flex items-center gap-1.5 text-sm text-muted-foreground">
            <span
              aria-hidden="true"
              className={cn("size-1.5 rounded-full", worker.active ? "bg-ok" : "bg-destructive")}
            />
            {worker.active ? "Indexing faces" : "Worker offline"}
          </p>
        )}
      </div>
      <div
        className="mt-3 flex h-2 w-full gap-0.5 overflow-hidden rounded-full bg-muted"
        role="img"
        aria-label={parts.map((p) => `${p.value} ${p.label}`).join(", ")}
      >
        {total > 0 &&
          parts
            .filter((p) => p.value > 0)
            .map((p) => (
              <div
                key={p.key}
                className={cn("h-full transition-[width] duration-700 ease-out", p.className)}
                style={{ width: `${(p.value / total) * 100}%` }}
              />
            ))}
      </div>
      <dl className="mt-3 flex flex-wrap gap-x-5 gap-y-1 text-sm text-muted-foreground">
        {parts
          .filter((p) => p.value > 0)
          .map((p) => (
            <div key={p.key} className="flex items-center gap-1.5">
              <span aria-hidden="true" className={cn("size-2 rounded-sm", p.className)} />
              <dt className="sr-only">{p.label}</dt>
              <dd>
                <span className="tabular text-foreground">{p.value}</span> {p.label}
              </dd>
            </div>
          ))}
      </dl>
    </div>
  );
}
