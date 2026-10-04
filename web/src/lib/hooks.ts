"use client";

import { useSyncExternalStore } from "react";

// Browser-only values read through useSyncExternalStore: the server render (static export)
// sees `undefined` = "not known yet", and the first client render picks up the real value
// without a hydration mismatch.

function subscribeStorage(onChange: () => void) {
  window.addEventListener("storage", onChange);
  return () => window.removeEventListener("storage", onChange);
}

/** The raw value of a localStorage key: undefined while rendering on the server, null if unset. */
export function useLocalStorage(key: string): string | null | undefined {
  return useSyncExternalStore(
    subscribeStorage,
    () => localStorage.getItem(key),
    () => undefined,
  );
}

function subscribeHash(onChange: () => void) {
  window.addEventListener("hashchange", onChange);
  return () => window.removeEventListener("hashchange", onChange);
}

/** The part of the URL after "#": undefined on the server. */
export function useHash(): string | undefined {
  return useSyncExternalStore(
    subscribeHash,
    () => window.location.hash.slice(1),
    () => undefined,
  );
}
