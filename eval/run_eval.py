"""
SpotMe measurement harness. Talks to a running API over HTTP, like a real client.

  python3 eval/run_eval.py upload   -> registers an organizer, creates an event, uploads eval/data/event/*
  python3 eval/run_eval.py wait     -> waits until the event is fully indexed, prints throughput
  python3 eval/run_eval.py accuracy -> runs every query through /eval-search, sweeps thresholds
  python3 eval/run_eval.py latency  -> real attendee searches, prints p50/p95 and the server timing split

State (event id, tokens) is kept in eval/out/state.json. Results go to eval/out/*.json.
The API must run with the "eval" profile for the accuracy step.
"""
import concurrent.futures as cf
import datetime as dt
import json
import os
import statistics
import sys
import time

import requests

API = os.environ.get("SPOTME_API", "http://localhost:8080")
HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(HERE, "data")
OUT = os.path.join(HERE, "out")
STATE = os.path.join(OUT, "state.json")


def load_state():
    with open(STATE) as f:
        return json.load(f)


def save(name, obj):
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, name), "w") as f:
        json.dump(obj, f, indent=1, default=str)


def pct(values, p):
    values = sorted(values)
    k = (len(values) - 1) * p / 100
    lo, hi = int(k), min(int(k) + 1, len(values) - 1)
    return values[lo] + (values[hi] - values[lo]) * (k - lo)


def upload():
    email = f"eval-{int(time.time())}@example.com"
    r = requests.post(f"{API}/api/auth/register", json={"email": email, "name": "Eval", "password": "password123"})
    token = r.json()["token"]
    h = {"Authorization": "Bearer " + token}
    expires = (dt.datetime.now(dt.timezone.utc) + dt.timedelta(days=7)).isoformat()
    ev = requests.post(f"{API}/api/events", headers=h, json={
        "title": "LFW evaluation event", "purposeNotice": "Accuracy measurement on the LFW benchmark.",
        "expiresAt": expires, "consentAttested": True}).json()["id"]

    files = sorted(os.listdir(os.path.join(DATA, "event")))
    photo_ids = {}
    times = []

    def send(name):
        with open(os.path.join(DATA, "event", name), "rb") as f:
            t = time.perf_counter()
            res = requests.post(f"{API}/api/events/{ev}/photos", headers=h, files={"file": (name, f, "image/jpeg")})
            return name, res.status_code, res.json().get("id"), time.perf_counter() - t

    start = time.perf_counter()
    with cf.ThreadPoolExecutor(4) as pool:  # the web page also sends 4 at a time
        for name, status, pid, secs in pool.map(send, files):
            assert status in (200, 201), (name, status)
            photo_ids[name] = pid
            times.append(secs * 1000)
    total = time.perf_counter() - start
    result = {"photos": len(files), "seconds": round(total, 2),
              "photos_per_min": round(len(files) / total * 60, 1),
              "request_ms_p50": round(pct(times, 50), 1), "request_ms_p95": round(pct(times, 95), 1)}
    print(json.dumps(result))
    save("state.json", {"event": ev, "token": token, "photo_ids": photo_ids})
    save("upload.json", result)


def wait():
    s = load_state()
    h = {"Authorization": "Bearer " + s["token"]}
    start = time.perf_counter()
    first = None
    while True:
        ev = requests.get(f"{API}/api/events/{s['event']}", headers=h).json()
        c = ev["counts"]
        if first is None:
            first = c
        if c["queued"] == 0 and c["processing"] == 0:
            break
        time.sleep(0.5)
    secs = time.perf_counter() - start
    done = c["ready"] + c["failed"] - first["ready"] - first["failed"]
    result = {"indexed": done, "seconds": round(secs, 1), "photos_per_min": round(done / secs * 60, 1) if secs else None,
              "ready": c["ready"], "failed": c["failed"], "faces": ev["faces"]}
    print(json.dumps(result))
    return result


def accuracy():
    s = load_state()
    h = {"Authorization": "Bearer " + s["token"], "Content-Type": "image/jpeg"}
    with open(os.path.join(DATA, "manifest.json")) as f:
        manifest = json.load(f)
    id_to_people = {s["photo_ids"][p["file"]]: set(p["people"]) for p in manifest["photos"]}

    rows = []
    for q in manifest["queries"]:
        with open(os.path.join(DATA, "queries", q["file"]), "rb") as f:
            res = requests.post(f"{API}/api/events/{s['event']}/eval-search", headers=h, data=f.read()).json()
        truth = {pid for pid, people in id_to_people.items() if q["person"] in people}
        rows.append({"person": q["person"], "present": q["present"], "split": q["split"], "status": res["status"],
                     "truth": sorted(truth),
                     "matches": [(m["photoId"], m["distance"]) for m in res.get("matches", [])]})
    save("accuracy_raw.json", rows)

    def score(split, t):
        tp = fp = fn = 0
        neg_queries = neg_fp = 0
        rejected = 0
        for r in rows:
            if r["split"] != split:
                continue
            if r["status"] != "ok":
                rejected += 1
                if r["present"]:
                    fn += len(r["truth"])
                else:
                    neg_queries += 1
                continue
            got = {pid for pid, d in r["matches"] if d <= t}
            truth = set(r["truth"])
            tp += len(got & truth)
            fp += len(got - truth)
            fn += len(truth - got)
            if not r["present"]:
                neg_queries += 1
                neg_fp += 1 if got else 0
        precision = tp / (tp + fp) if tp + fp else None
        recall = tp / (tp + fn) if tp + fn else None
        return {"threshold": t, "tp": tp, "fp": fp, "fn": fn,
                "precision": round(precision, 4) if precision is not None else None,
                "recall": round(recall, 4) if recall is not None else None,
                "negative_queries": neg_queries, "negative_queries_with_any_result": neg_fp,
                "rejected_queries": rejected}

    thresholds = [round(0.40 + 0.025 * i, 3) for i in range(17)]  # 0.40 .. 0.80
    tune = [score("tune", t) for t in thresholds]
    test = [score("test", t) for t in thresholds]
    save("accuracy.json", {"tune": tune, "test": test})
    print("threshold  tune P/R  test P/R  test FP  test neg-FP")
    for a, b in zip(tune, test):
        print(f"{a['threshold']:.3f}  {a['precision']} / {a['recall']}   {b['precision']} / {b['recall']}   "
              f"{b['fp']}   {b['negative_queries_with_any_result']}/{b['negative_queries']}")


def latency(n=200):
    s = load_state()
    h = {"Authorization": "Bearer " + s["token"]}
    requests.post(f"{API}/api/events/{s['event']}/publish", headers=h)
    invite = requests.post(f"{API}/api/events/{s['event']}/invites", headers=h, json={"label": "Latency"}).json()
    notice = requests.get(f"{API}/api/join/{invite['token']}").json()
    session = requests.post(f"{API}/api/join/{invite['token']}/consent",
                            json={"noticeVersion": notice["noticeVersion"], "agree": True}).json()["sessionToken"]
    ah = {"Authorization": "Bearer " + session, "Content-Type": "image/jpeg"}
    queries = sorted(os.listdir(os.path.join(DATA, "queries")))
    blobs = [open(os.path.join(DATA, "queries", q), "rb").read() for q in queries]
    total, parts = [], {"decode": [], "faces": [], "sql": []}
    for i in range(n + 10):
        t = time.perf_counter()
        res = requests.post(f"{API}/api/attendee/search", headers=ah, data=blobs[i % len(blobs)])
        ms = (time.perf_counter() - t) * 1000
        if i < 10:
            continue  # warm-up
        if res.status_code != 200:
            continue
        total.append(ms)
        for part in res.headers["Server-Timing"].split(","):
            name, dur = part.strip().split(";dur=")
            parts[name].append(float(dur))
    result = {"requests": len(total),
              "total_ms": {"p50": round(pct(total, 50), 1), "p95": round(pct(total, 95), 1), "max": round(max(total), 1)},
              **{k + "_ms": {"p50": round(pct(v, 50), 1), "p95": round(pct(v, 95), 1)} for k, v in parts.items()}}
    print(json.dumps(result))
    save("latency.json", result)


if __name__ == "__main__":
    {"upload": upload, "wait": wait, "accuracy": accuracy, "latency": latency}[sys.argv[1]]()
