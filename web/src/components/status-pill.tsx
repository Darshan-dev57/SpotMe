import { cn } from "@/lib/utils";

const LABEL: Record<string, string> = {
  draft: "Draft",
  published: "Published",
  deleting: "Deleting",
  purged: "Deleted",
};

/** Small text label with a coloured dot. Used for event status only, never as decoration. */
export function StatusPill({ status }: { status: string }) {
  return (
    <span className="inline-flex shrink-0 items-center gap-1.5 text-xs font-medium text-muted-foreground">
      <span
        aria-hidden="true"
        className={cn(
          "size-1.5 rounded-full",
          status === "published" && "bg-ok",
          status === "draft" && "bg-busy",
          (status === "deleting" || status === "purged") && "bg-destructive",
        )}
      />
      {LABEL[status] ?? status}
    </span>
  );
}
