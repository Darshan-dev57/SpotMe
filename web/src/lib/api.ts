// Small fetch wrapper around the SpotMe API.

const BASE = process.env.NEXT_PUBLIC_API_URL ?? "";

export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
  ) {
    super(message);
  }
}

type Options = {
  method?: string;
  token?: string | null;
  json?: unknown;
  body?: BodyInit;
  contentType?: string;
};

async function request(path: string, opts: Options = {}): Promise<Response> {
  const headers: Record<string, string> = {};
  if (opts.token) headers.Authorization = `Bearer ${opts.token}`;
  let body = opts.body;
  if (opts.json !== undefined) {
    headers["Content-Type"] = "application/json";
    body = JSON.stringify(opts.json);
  } else if (opts.contentType) {
    headers["Content-Type"] = opts.contentType;
  }
  let res: Response;
  try {
    res = await fetch(BASE + path, { method: opts.method ?? (body ? "POST" : "GET"), headers, body });
  } catch {
    throw new ApiError(0, "offline", "Can't reach SpotMe right now. Check your connection and try again.");
  }
  if (!res.ok) {
    let code = "error";
    let message = `Request failed (${res.status}).`;
    try {
      const data = await res.json();
      code = data.code ?? code;
      message = data.message ?? message;
    } catch {
      // not JSON, keep the generic message
    }
    throw new ApiError(res.status, code, message);
  }
  return res;
}

export async function api<T>(path: string, opts: Options = {}): Promise<T> {
  const res = await request(path, opts);
  if (res.status === 204) return undefined as T;
  return res.json() as Promise<T>;
}

export async function apiBlob(path: string, opts: Options = {}): Promise<Blob> {
  return (await request(path, opts)).blob();
}

/** Media links come back as "/api/media/..."; make them absolute when the API is on another origin. */
export function mediaUrl(path: string): string {
  return BASE + path;
}

export function saveBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

// ---------- API shapes ----------

export type Counts = { queued: number; processing: number; ready: number; failed: number };

export type EventSummary = {
  id: string;
  title: string;
  status: "draft" | "published" | "deleting" | "purged";
  expiresAt: string;
  createdAt: string;
  photoCount: number;
  readyCount: number;
};

export type EventDetail = {
  id: string;
  title: string;
  purposeNotice: string;
  noticeVersion: number;
  status: EventSummary["status"];
  expiresAt: string;
  createdAt: string;
  publishedAt: string | null;
  counts: Counts;
  faces: number;
  hidden: number;
  publicPhotos: number;
  pendingRemovals: number;
  worker: { active: boolean; lastSeen: string | null };
};

export type Photo = {
  id: string;
  status: "queued" | "processing" | "ready" | "failed";
  faceCount: number | null;
  errorCode: string | null;
  hidden: boolean;
  suppressed: boolean;
  public: boolean;
  wrongMatchReports: number;
  thumbUrl?: string;
};

export type Invite = {
  id: string;
  label: string;
  consentedAt: string | null;
  withdrawnAt: string | null;
  revokedAt: string | null;
  createdAt: string;
};

export type RemovalRequest = {
  id: string;
  status: "received" | "approved" | "rejected" | "completed";
  mode: "photos" | "solo";
  note: string | null;
  requestedBy: string | null;
  createdAt: string;
  photos: { id: string; status: string; thumbUrl?: string }[];
};

export type Notice = {
  eventTitle: string;
  organizer: string;
  purposeNotice: string;
  noticeVersion: number;
  expiresAt: string;
  published: boolean;
  invitedAs: string;
  withdrawn: boolean;
  sample: boolean;
};

/** A photo a guest may see, with short-lived signed links. */
export type GuestPhoto = { id: string; thumbUrl: string; downloadUrl: string };
/** faceId: the face that matched; solo: the guest is the only face in the photo. */
export type Match = GuestPhoto & { faceId: number; solo: boolean };
/** Photos the organizer shared with every guest of the event. */
export type EventPhotos = { total: number; photos: GuestPhoto[] };
export type SearchResult = { count: number; photos: Match[]; stillIndexing: boolean };

export type RemovalCreated = {
  id: string;
  hidden: number;
  keptGroupPhotos: number;
};

export type AlertCheck = {
  eventTitle: string;
  expiresAt: string;
  seenUntil: string;
  checkedAt: string;
  newCount: number;
  photos: { id: string; thumbUrl: string; downloadUrl: string }[];
};

export type DriveImport = {
  id: string;
  folderUrl: string;
  status: "listing" | "importing" | "done" | "failed";
  found: number;
  overLimit: number;
  skipped: number;
  queued: number;
  imported: number;
  duplicates: number;
  failed: number;
  error: string | null;
  createdAt: string;
  finishedAt: string | null;
  failures: { name: string; error: string }[];
};
