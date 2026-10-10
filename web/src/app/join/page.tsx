"use client";

import { useEffect, useState } from "react";
import { SiteHeader } from "@/components/brand";
import { NoticeStep } from "@/components/attendee/notice-step";
import { Results } from "@/components/attendee/results";
import { SelfieStep } from "@/components/attendee/selfie-step";
import { Spinner } from "@/components/ui/spinner";
import { ApiError, Notice, SearchResult, api } from "@/lib/api";
import { useHash } from "@/lib/hooks";
import { getAttendeeToken, setAttendeeToken } from "@/lib/session";

type Stage =
  | { kind: "loading" }
  | { kind: "invalid"; message: string }
  | { kind: "notice" }
  | { kind: "declined" }
  | { kind: "selfie" }
  | { kind: "results"; result: SearchResult }
  | { kind: "left" };

/**
 * The guest's page. The invite token sits after the "#", so it never reaches any server log;
 * only the API calls below send it.
 */
export default function JoinPage() {
  const hash = useHash();
  const invite = hash ?? "";
  const [notice, setNotice] = useState<Notice | null>(null);
  const [stage, setStage] = useState<Stage>({ kind: "loading" });

  useEffect(() => {
    if (hash === undefined) return; // not in the browser yet
    const load = hash
      ? api<Notice>(`/api/join/${encodeURIComponent(hash)}`)
      : Promise.reject(new ApiError(404, "no_invite", "This page needs the personal link from your invite."));
    load
      .then((n) => {
        setNotice(n);
        if (n.withdrawn) setStage({ kind: "left" });
        else setStage(getAttendeeToken(hash) ? { kind: "selfie" } : { kind: "notice" });
      })
      .catch((e) =>
        setStage({ kind: "invalid", message: e instanceof ApiError ? e.message : "Something went wrong." }),
      );
  }, [hash]);

  const session = invite ? getAttendeeToken(invite) : null;

  function lostAccess() {
    setAttendeeToken(invite, null);
    setStage({ kind: "notice" });
  }

  return (
    <>
      <SiteHeader />
      <main className="mx-auto w-full max-w-3xl px-5 pt-2 pb-20 sm:px-8">
        {stage.kind === "loading" && (
          <div className="flex items-center gap-2 pt-10 text-muted-foreground">
            <Spinner /> Opening your invite
          </div>
        )}

        {stage.kind === "invalid" && (
          <div className="pt-8">
            <h1 className="text-2xl font-semibold">This invite can&apos;t be opened</h1>
            <p className="mt-2 max-w-prose text-muted-foreground">{stage.message} Ask the organizer for a new link.</p>
          </div>
        )}

        {notice && stage.kind === "notice" && (
          <NoticeStep
            notice={notice}
            invite={invite}
            onAgreed={(token) => {
              setAttendeeToken(invite, token);
              setStage({ kind: "selfie" });
            }}
            onDeclined={() => setStage({ kind: "declined" })}
          />
        )}

        {stage.kind === "declined" && (
          <div className="pt-8">
            <h1 className="text-2xl font-semibold">No problem</h1>
            <p className="mt-2 max-w-prose text-muted-foreground">
              Nothing was searched and no photo of you was taken. You can open your link again any time before the
              event is deleted.
            </p>
          </div>
        )}

        {notice && session && stage.kind === "selfie" && (
          <SelfieStep
            notice={notice}
            token={session}
            onResult={(result) => setStage({ kind: "results", result })}
            onLostAccess={lostAccess}
          />
        )}

        {notice && session && stage.kind === "results" && (
          <Results
            notice={notice}
            token={session}
            result={stage.result}
            onAgain={() => setStage({ kind: "selfie" })}
            onLeft={() => {
              setAttendeeToken(invite, null);
              setStage({ kind: "left" });
            }}
          />
        )}

        {stage.kind === "left" && (
          <div className="pt-8">
            <h1 className="text-2xl font-semibold">You have left this event</h1>
            <p className="mt-2 max-w-prose text-muted-foreground">
              Your link no longer works and you can&apos;t search this album. Removal requests you sent are still
              handled by the organizer.
            </p>
          </div>
        )}
      </main>
    </>
  );
}
