// Live check of the ingestion paths against a running SpotMe (default: the live site).
//
//   npm i playwright-core   (once, anywhere on NODE_PATH; needs a Chromium binary)
//   SPOTME_API=https://spotme.sites.tab.bot FOLDER=/path/to/folder SELFIE=behnken.jpg \
//   DRIVE_LINK=https://drive.google.com/drive/folders/... node eval/ingest_check.mjs
//
// 1. folder upload through the real organizer page (phone-size headless Chromium), then indexing and a
//    selfie search over the API;
// 2. Drive import of a public folder over the API, polled until done, with the box's memory sampled.
// Writes eval/out/ingest_live.json.
import { chromium } from "playwright-core";
import fs from "fs";
import path from "path";

const BASE = process.env.SPOTME_API ?? "https://spotme.sites.tab.bot";
const OUT = path.join(path.dirname(new URL(import.meta.url).pathname), "out", "ingest_live.json");
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function call(p, { token, json, raw, type } = {}) {
  const headers = {};
  if (token) headers.Authorization = `Bearer ${token}`;
  let body;
  if (json !== undefined) { headers["Content-Type"] = "application/json"; body = JSON.stringify(json); }
  if (raw) { headers["Content-Type"] = type; body = raw; }
  const res = await fetch(BASE + p, { method: body ? "POST" : "GET", headers, body });
  const text = await res.text();
  return { status: res.status, body: text ? JSON.parse(text) : null };
}

async function organizerWithEvent(title) {
  const email = `ingest-${Date.now()}@example.com`;
  const token = (await call("/api/auth/register", { json: { email, name: "Ingest check", password: "Ingest-check-123" } })).body.token;
  const event = (await call("/api/events", { token, json: { title, purposeNotice: "Ingestion check. Deleted after a week.",
    expiresAt: new Date(Date.now() + 7 * 86400e3).toISOString(), consentAttested: true } })).body.id;
  return { token, event };
}

async function waitIndexed(token, event) {
  for (;;) {
    const d = (await call(`/api/events/${event}`, { token })).body;
    if (d.counts.queued + d.counts.processing === 0) return d;
    await sleep(3000);
  }
}

async function search(token, event, selfie) {
  await call(`/api/events/${event}/publish`, { token, json: {} });
  const invite = (await call(`/api/events/${event}/invites`, { token, json: { label: "Check" } })).body.token;
  const notice = (await call(`/api/join/${invite}`)).body;
  const guest = (await call(`/api/join/${invite}/consent`, { json: { noticeVersion: notice.noticeVersion, agree: true } })).body.sessionToken;
  return (await call("/api/attendee/search", { token: guest, raw: fs.readFileSync(selfie), type: "image/jpeg" })).body;
}

const result = { base: BASE, at: new Date().toISOString() };

if (process.env.FOLDER) {
  const { token, event } = await organizerWithEvent("Folder upload check");
  const browser = await chromium.launch({ executablePath: process.env.CHROMIUM ?? "/usr/bin/chromium", args: ["--no-sandbox"] });
  const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, isMobile: true });
  await ctx.addInitScript(([t]) => localStorage.setItem("spotme.organizer", JSON.stringify({ token: t, name: "Ingest check" })), [token]);
  const page = await ctx.newPage();
  await page.goto(`${BASE}/organizer/event?id=${event}`, { waitUntil: "networkidle" });
  const t0 = Date.now();
  await page.locator("input[type=file][webkitdirectory]").setInputFiles(process.env.FOLDER);
  await page.waitForSelector("text=Upload finished", { timeout: 600000 });
  const panel = await page.locator("[aria-live=polite]").first().innerText();
  await browser.close();
  const detail = await waitIndexed(token, event);
  const photos = (await call(`/api/events/${event}/photos`, { token })).body;
  const found = await search(token, event, process.env.SELFIE);
  result.folder = { uploadSeconds: (Date.now() - t0) / 1000, panel, counts: detail.counts, faces: detail.faces,
    photos: photos.map((p) => ({ width: p.width, height: p.height, faces: p.faceCount, matched: found.photos.some((m) => m.id === p.id) })),
    searchResults: found.photos.length };
  console.log("folder", JSON.stringify(result.folder));
}

if (process.env.DRIVE_LINK) {
  const { token, event } = await organizerWithEvent("Drive import check");
  const started = Date.now();
  const start = await call(`/api/events/${event}/drive-imports`, { token, json: { link: process.env.DRIVE_LINK } });
  const memory = [];
  let imp = start.body;
  while (imp.status === "listing" || imp.status === "importing") {
    await sleep(15000);
    imp = (await call(`/api/events/${event}/drive-imports`, { token })).body[0];
    const health = (await call("/actuator/health")).body;
    if (health?.components?.box) memory.push(health.components.box.details);
  }
  const detail = await waitIndexed(token, event);
  result.drive = { status: imp.status, found: imp.found, imported: imp.imported, duplicates: imp.duplicates, failed: imp.failed,
    skipped: imp.skipped, overLimit: imp.overLimit, seconds: (Date.parse(imp.finishedAt) - Date.parse(imp.createdAt)) / 1000,
    wallSeconds: (Date.now() - started) / 1000, counts: detail.counts, faces: detail.faces,
    memory: memory.length ? { lowestAvailableMb: Math.min(...memory.map((m) => m.memoryAvailableMb)),
      highestProcessRssMb: Math.max(...memory.map((m) => m.processRssMb)), highestSwapMb: Math.max(...memory.map((m) => m.swapUsedMb)), samples: memory.length } : null };
  console.log("drive", JSON.stringify(result.drive));
}

fs.writeFileSync(OUT, JSON.stringify(result, null, 2) + "\n");
console.log("wrote", OUT);
