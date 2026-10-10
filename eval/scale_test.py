"""
Scale test of the hosted app with the full LFW set (13,233 photos of 5,749 people).

  python3 eval/scale_test.py prepare   # needs eval/data/scale/lfw (the extracted lfw.tgz)
  python3 eval/scale_test.py upload    # one event, every event photo through the real API
  python3 eval/scale_test.py wait      # until the job queue is drained
  python3 eval/scale_test.py search    # one guest per query, so the per-guest rate limit never applies
  python3 eval/scale_test.py summary   # writes eval/out/scale.json

Split (seed 11): every person with 2 or more photos keeps one photo back as their selfie; 300 people
with a single photo are kept out of the event completely and search as strangers. Everything else
goes into the event. Raw logs stay in eval/data/scale/ (ignored by git).
"""
import concurrent.futures as cf
import datetime as dt
import json
import os
import random
import secrets
import sys
import threading
import time

import requests

from run_eval import pct, save

API = os.environ.get("SPOTME_API", "https://spotme.sites.tab.bot")
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATA = os.path.join(ROOT, "eval", "data", "scale")
LFW = os.path.join(DATA, "lfw")
LOGS = os.environ.get("SPOTME_SCALE_LOGS", DATA)  # manifest, state and raw logs
SEED = 11
STRANGERS = 300
UPLOAD_THREADS = 4  # the organizer page uploads 4 at a time


def path(name):
    return os.path.join(LOGS, name)


def load(name):
    with open(path(name)) as f:
        return json.load(f)


def dump(name, obj):
    with open(path(name), "w") as f:
        json.dump(obj, f)


def log_line(name, obj):
    with open(path(name), "a") as f:
        f.write(json.dumps(obj) + "\n")


def read_lines(name):
    if not os.path.exists(path(name)):
        return []
    with open(path(name)) as f:
        return [json.loads(line) for line in f if line.strip()]


def prepare():
    people = {p: sorted(os.listdir(os.path.join(LFW, p))) for p in sorted(os.listdir(LFW))}
    rng = random.Random(SEED)
    singles = [p for p, files in people.items() if len(files) == 1]
    strangers = set(rng.sample(singles, STRANGERS))
    event, selfies, stranger_selfies = [], {}, {}
    for person, files in people.items():
        files = [f"{person}/{f}" for f in files]
        if person in strangers:
            stranger_selfies[person] = files[0]
        elif len(files) >= 2:
            held = rng.choice(files)
            selfies[person] = held
            event += [f for f in files if f != held]
        else:
            event += files
    rng.shuffle(event)
    manifest = {"seed": SEED, "people": len(people), "images": sum(len(f) for f in people.values()),
                "event_photos": event, "selfies": selfies, "strangers": stranger_selfies}
    dump("manifest.json", manifest)
    print(f"{len(event)} event photos, {len(selfies)} selfies, {len(stranger_selfies)} strangers")


def organizer():
    return {"Authorization": "Bearer " + load("state.json")["token"]}


def monitor(event_id, stop):
    """Every 15 s: queue counts, box memory and disk, server errors, and one probe search."""
    probe = os.path.join(LFW, "George_W_Bush", "George_W_Bush_0001.jpg")
    invite = requests.post(f"{API}/api/events/{event_id}/invites", headers=organizer(), json={"label": "probe"}, timeout=30).json()
    session = requests.post(f"{API}/api/join/{invite['token']}/consent", json={"noticeVersion": 1, "agree": True}, timeout=30).json()["sessionToken"]
    probe_headers = {"Authorization": "Bearer " + session, "Content-Type": "image/jpeg"}
    with open(probe, "rb") as f:
        probe_body = f.read()
    tick = 0
    while not stop.is_set():
        row = {"t": time.time()}
        try:
            ev = requests.get(f"{API}/api/events/{event_id}", headers=organizer(), timeout=30).json()
            row["counts"], row["faces"] = ev["counts"], ev["faces"]
            health = requests.get(f"{API}/actuator/health", timeout=30).json()
            row["health"] = health["status"]
            row["box"] = health["components"]["box"]["details"]
            row["disk_free"] = health["components"]["diskSpace"]["details"]["free"]
            res = requests.get(f"{API}/actuator/metrics/http.server.requests", params={"tag": "outcome:SERVER_ERROR"}, timeout=30)
            row["server_errors"] = res.json()["measurements"][0]["value"] if res.status_code == 200 else 0
            if tick % 4 == 0:  # once a minute, stays under the 10 per minute guest limit
                t = time.perf_counter()
                res = requests.post(f"{API}/api/attendee/search", headers=probe_headers, data=probe_body, timeout=60)
                row["probe_ms"] = round((time.perf_counter() - t) * 1000, 1)
                row["probe_status"] = res.status_code
                row["probe_timing"] = res.headers.get("Server-Timing")
        except Exception as e:  # the monitor must never stop the run
            row["error"] = repr(e)
        log_line("monitor.jsonl", row)
        tick += 1
        stop.wait(15)


def upload():
    manifest = load("manifest.json")
    if os.path.exists(path("state.json")):
        state = load("state.json")
    else:
        email = f"scale-test-{int(time.time())}@example.com"
        password = secrets.token_urlsafe(18)
        token = requests.post(f"{API}/api/auth/register", json={"email": email, "name": "Scale test", "password": password}, timeout=30).json()["token"]
        expires = (dt.datetime.now(dt.timezone.utc) + dt.timedelta(days=89)).isoformat()
        ev = requests.post(f"{API}/api/events", headers={"Authorization": "Bearer " + token}, json={
            "title": "Scale test", "purposeNotice": "Scale test with the public LFW research set (13,233 photos).",
            "expiresAt": expires, "consentAttested": True}, timeout=30).json()
        requests.post(f"{API}/api/events/{ev['id']}/publish", headers={"Authorization": "Bearer " + token}, timeout=30)
        state = {"email": email, "password": password, "token": token, "event": ev["id"], "created": time.time()}
        dump("state.json", state)
    event_id = state["event"]
    done = {row["file"] for row in read_lines("uploads.jsonl") if row.get("status") in (200, 201)}
    todo = [f for f in manifest["event_photos"] if f not in done]
    print(f"event {event_id}: {len(done)} already uploaded, {len(todo)} to go", flush=True)

    stop = threading.Event()
    watcher = threading.Thread(target=monitor, args=(event_id, stop), daemon=True)
    watcher.start()
    lock = threading.Lock()

    def send(name):
        with open(os.path.join(LFW, name), "rb") as f:
            body = f.read()
        attempts, row = 0, {}
        while attempts < 5:
            attempts += 1
            t = time.perf_counter()
            try:
                res = requests.post(f"{API}/api/events/{event_id}/photos", headers=organizer(),
                                    files={"file": (os.path.basename(name), body, "image/jpeg")}, timeout=120)
                ms = (time.perf_counter() - t) * 1000
                row = {"file": name, "status": res.status_code, "ms": round(ms, 1), "attempts": attempts, "t": time.time()}
                if res.status_code in (200, 201):
                    data = res.json()
                    row["photo"], row["result"] = data.get("id") or data.get("photoId"), data.get("status")
                    break
                row["error"] = res.text[:200]
                if res.status_code < 500 and res.status_code != 429:
                    break
            except requests.RequestException as e:
                row = {"file": name, "status": None, "error": repr(e)[:200], "attempts": attempts, "t": time.time()}
            time.sleep(2 ** attempts)
        with lock:
            log_line("uploads.jsonl", row)
        return row

    start = time.time()
    if not os.path.exists(path("upload_start.json")):
        dump("upload_start.json", {"t": start})
    with cf.ThreadPoolExecutor(UPLOAD_THREADS) as pool:
        for i, row in enumerate(pool.map(send, todo), 1):
            if i % 500 == 0:
                print(f"{i}/{len(todo)} uploaded, {time.time() - start:.0f} s", flush=True)
    dump("upload_end.json", {"t": time.time()})
    print(f"upload finished in {time.time() - start:.0f} s, waiting for the queue", flush=True)
    wait(stop)


def wait(stop=None):
    state = load("state.json")
    if stop is None:
        stop = threading.Event()
        threading.Thread(target=monitor, args=(state["event"], stop), daemon=True).start()
    while True:
        c = requests.get(f"{API}/api/events/{state['event']}", headers=organizer(), timeout=30).json()["counts"]
        if c["queued"] == 0 and c["processing"] == 0:
            break
        time.sleep(10)
    dump("drained.json", {"t": time.time(), "counts": c})
    stop.set()
    print("queue drained", c, flush=True)


def search(log="searches.jsonl"):
    manifest = load("manifest.json")
    state = load("state.json")
    event_id = state["event"]
    queries = [("member", p, f) for p, f in manifest["selfies"].items()] + \
              [("stranger", p, f) for p, f in manifest["strangers"].items()]
    random.Random(SEED).shuffle(queries)
    done = {row["person"] for row in read_lines(log)}
    for i, (kind, person, name) in enumerate(queries):
        if person in done:
            continue
        row = {"kind": kind, "person": person, "selfie": name}
        try:
            invite = requests.post(f"{API}/api/events/{event_id}/invites", headers=organizer(), json={"label": f"q{i}"}, timeout=30).json()
            session = requests.post(f"{API}/api/join/{invite['token']}/consent", json={"noticeVersion": 1, "agree": True}, timeout=30).json()["sessionToken"]
            with open(os.path.join(LFW, name), "rb") as f:
                body = f.read()
            t = time.perf_counter()
            res = requests.post(f"{API}/api/attendee/search", headers={"Authorization": "Bearer " + session, "Content-Type": "image/jpeg"},
                                data=body, timeout=60)
            row["ms"] = round((time.perf_counter() - t) * 1000, 1)
            row["status"] = res.status_code
            row["timing"] = res.headers.get("Server-Timing")
            if res.status_code == 200:
                row["photos"] = [p["id"] for p in res.json()["photos"]]
            else:
                row["error"] = res.text[:200]
        except Exception as e:
            row["error"] = repr(e)[:200]
        row["t"] = time.time()
        log_line(log, row)
        if (i + 1) % 100 == 0:
            print(f"{i + 1}/{len(queries)} searches", flush=True)
    print("searches done", flush=True)


def summary():
    result = build_summary()
    print(json.dumps(result, indent=1))
    save("scale.json", result)


MAX_RESULTS = 200


def lat(values):
    if not values:
        return None
    return {"p50": round(pct(values, 50), 1), "p95": round(pct(values, 95), 1), "p99": round(pct(values, 99), 1),
            "max": round(max(values), 1)}


def latency(searches):
    answered = [s for s in searches if s.get("status") == 200]
    parts = {"decode": [], "faces": [], "sql": []}
    for s in answered:
        for part in (s.get("timing") or "").split(","):
            if ";dur=" in part:
                key, dur = part.strip().split(";dur=")
                parts[key].append(float(dur))
    return {"searches": len(answered), "client_total_ms": lat([s["ms"] for s in answered]),
            **{"server_" + k + "_ms": lat(v) for k, v in parts.items()}}


def same_results(before, after):
    old = {s["person"]: set(s.get("photos", [])) for s in before if s.get("status") == 200}
    both = [s for s in after if s.get("status") == 200 and s["person"] in old]
    return {"compared": len(both), "identical": sum(1 for s in both if set(s["photos"]) == old[s["person"]])}


def accuracy(searches, photos_of):
    members = [s for s in searches if s["kind"] == "member"]
    strangers = [s for s in searches if s["kind"] == "stranger"]
    returned = correct = truth = truth_capped = found_any = 0
    refused = {}
    for s in members:
        mine = photos_of.get(s["person"], set())
        truth += len(mine)
        truth_capped += min(len(mine), MAX_RESULTS)
        if s.get("status") != 200:
            refused[s.get("status")] = refused.get(s.get("status"), 0) + 1
            continue
        got = set(s["photos"])
        returned += len(got)
        hits = len(got & mine)
        correct += hits
        found_any += hits > 0
    answered_strangers = [s for s in strangers if s.get("status") == 200]
    stranger_hits = [s for s in answered_strangers if s["photos"]]
    stranger_refused = {}
    for s in strangers:
        if s.get("status") != 200:
            stranger_refused[s.get("status")] = stranger_refused.get(s.get("status"), 0) + 1
    return {"threshold": 0.55, "max_results": MAX_RESULTS,
            "member_queries": len(members), "member_queries_refused": refused,
            "precision": round(correct / returned, 4) if returned else None,
            "recall": round(correct / truth, 4) if truth else None,
            "recall_within_result_cap": round(correct / truth_capped, 4) if truth_capped else None,
            "members_with_at_least_one_correct_photo": found_any,
            "photos_returned": returned, "photos_correct": correct, "photos_wrong": returned - correct,
            "stranger_queries": len(strangers), "stranger_queries_refused": stranger_refused,
            "strangers_with_any_result": len(stranger_hits),
            "stranger_false_match_rate": round(len(stranger_hits) / len(answered_strangers), 4) if answered_strangers else None,
            "stranger_photos_returned": sum(len(s["photos"]) for s in answered_strangers),
            "scoring": "per photo; a returned photo counts as correct only if the searched person is the labelled "
                       "person in it, so a person who is only in the background counts as wrong; refused selfies "
                       "(422, more than one face or none) count as misses in recall"}


def build_summary():
    manifest = load("manifest.json")
    state = load("state.json")
    uploads = read_lines("uploads.jsonl")
    mon = read_lines("monitor.jsonl")
    upload_start, upload_end = load("upload_start.json")["t"], load("upload_end.json")["t"]
    drained = load("drained.json")

    # which people are in each photo (identical files share one photo id)
    owners = {}
    for row in uploads:
        if row.get("photo"):
            owners.setdefault(row["photo"], set()).add(row["file"].split("/")[0])
    photos_of = {}
    for photo, people in owners.items():
        for person in people:
            photos_of.setdefault(person, set()).add(photo)

    ok = [r for r in uploads if r.get("status") in (200, 201)]
    upload_ms = [r["ms"] for r in ok]
    upload_s = upload_end - upload_start
    index_s = drained["t"] - upload_start
    ready = drained["counts"]["ready"]

    # pure indexing rate after the last upload, from the monitor's ready counts
    after = [m for m in mon if "counts" in m and upload_end <= m["t"] <= drained["t"] + 15]
    tail_rate = None
    if len(after) >= 2 and after[-1]["t"] > after[0]["t"]:
        tail_rate = (after[-1]["counts"]["ready"] - after[0]["counts"]["ready"]) / ((after[-1]["t"] - after[0]["t"]) / 60)

    before = read_lines("searches.jsonl")
    after_fix = read_lines("searches_after_fix.jsonl")
    final = after_fix or before

    during = [m for m in mon if m.get("probe_status") == 200 and m["t"] < drained["t"]]
    boxes = [m["box"] for m in mon if "box" in m]
    disks = [m["disk_free"] for m in mon if "disk_free" in m]

    return {
        "api": API,
        "measured_at": dt.datetime.fromtimestamp(upload_start, dt.timezone.utc).isoformat(timespec="seconds"),
        "dataset": {"source": "LFW, full set (http://vis-www.cs.umass.edu/lfw/)", "images": manifest["images"],
                    "people": manifest["people"], "seed": manifest["seed"],
                    "event_photos": len(manifest["event_photos"]),
                    "held_out_selfies": len(manifest["selfies"]), "strangers_kept_out": len(manifest["strangers"]),
                    "note": "every person with 2+ photos keeps one back as the selfie; 300 single-photo people are not in the event"},
        "event": {"id": state["event"], "photos_ready": ready, "photos_failed": drained["counts"]["failed"],
                  "faces": requests.get(f"{API}/api/events/{state['event']}", headers=organizer(), timeout=30).json()["faces"],
                  "distinct_photo_ids": len(owners)},
        "upload": {"threads": UPLOAD_THREADS, "requests_ok": len(ok),
                   "requests_failed_final": len(uploads) - len(ok),
                   "retried": sum(1 for r in uploads if r.get("attempts", 1) > 1),
                   "duplicates": sum(1 for r in ok if r.get("result") == "duplicate"),
                   "seconds": round(upload_s, 1), "photos_per_minute": round(len(ok) / upload_s * 60, 1),
                   "request_ms": lat(upload_ms)},
        "indexing": {"seconds_from_first_upload_to_drained": round(index_s, 1),
                     "photos_per_minute_overall": round(ready / index_s * 60, 1),
                     "photos_per_minute_after_uploads_ended": round(tail_rate, 1) if tail_rate else None,
                     "largest_queue_seen": max((m["counts"]["queued"] + m["counts"]["processing"] for m in mon if "counts" in m), default=None),
                     "worker": "1 thread, 1 face engine, 2 OpenCV threads (deploy/start.sh)"},
        "accuracy": accuracy(final, photos_of),
        "search_latency_before_fix": latency(before),
        "search_latency_after_fix": latency(after_fix) if after_fix else None,
        "same_photos_before_and_after_fix": same_results(before, after_fix) if after_fix else None,
        "search_latency_during_indexing": {"probes": len(during), "client_total_ms": lat([m["probe_ms"] for m in during]),
                                           "note": "one probe search a minute while photos were being uploaded and indexed (before the fix)"},
        "box": {"memory_total_mb": boxes[0]["memoryTotalMb"] if boxes else None,
                "min_memory_available_mb": min(b["memoryAvailableMb"] for b in boxes) if boxes else None,
                "max_swap_used_mb": max(b["swapUsedMb"] for b in boxes) if boxes else None,
                "max_process_rss_mb": max(b["processRssMb"] for b in boxes) if boxes else None,
                "disk_used_by_run_mb": round((disks[0] - min(disks)) / 1e6) if disks else None,
                "disk_free_gb_after": round(disks[-1] / 1e9, 1) if disks else None,
                "server_errors": max((m.get("server_errors", 0) for m in mon), default=0),
                "health_not_up": sum(1 for m in mon if m.get("health") not in (None, "UP")),
                "monitor_errors": sum(1 for m in mon if "error" in m), "samples": len(mon)},
        "note": "client times are measured from a machine outside the box, so they include the network round trip",
    }


if __name__ == "__main__":
    {"prepare": prepare, "upload": upload, "wait": wait, "search": search, "summary": summary}[sys.argv[1]](*sys.argv[2:])
