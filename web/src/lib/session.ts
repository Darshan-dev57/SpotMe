// Where the browser keeps its tokens.
// Organizer: localStorage, so a refresh keeps you signed in on your own device.
// Attendee: sessionStorage, so the session ends when the tab closes.

export const ORGANIZER_KEY = "spotme.organizer";

export type OrganizerSession = { token: string; name: string };

export function getOrganizer(): OrganizerSession | null {
  if (typeof window === "undefined") return null;
  const raw = localStorage.getItem(ORGANIZER_KEY);
  return raw ? (JSON.parse(raw) as OrganizerSession) : null;
}

export function setOrganizer(session: OrganizerSession | null) {
  if (session) localStorage.setItem(ORGANIZER_KEY, JSON.stringify(session));
  else localStorage.removeItem(ORGANIZER_KEY);
  // the "storage" event only fires in other tabs, so tell this tab too
  window.dispatchEvent(new StorageEvent("storage", { key: ORGANIZER_KEY }));
}

export function getAttendeeToken(invite: string): string | null {
  return sessionStorage.getItem(`spotme.attendee.${invite.slice(0, 12)}`);
}

export function setAttendeeToken(invite: string, token: string | null) {
  const key = `spotme.attendee.${invite.slice(0, 12)}`;
  if (token) sessionStorage.setItem(key, token);
  else sessionStorage.removeItem(key);
}
