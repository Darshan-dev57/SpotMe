"""
Checks the live site end to end and measures it, from the outside, like a visitor would.

  python3 eval/hosted_check.py   (needs scripts/fetch-sample-photos.sh to have run)

1. registers an organizer, creates an event and uploads the 36 sample photos (2 at a time),
2. times how long the box takes to index them,
3. publishes, invites three guests and runs searches with three sample selfies,
4. deletes the event again so the box is left as it was.

The search rate limit is 10 per guest per minute, so each guest searches at most 9 times.
Results go to eval/out/hosted.json.
"""
import concurrent.futures as cf
import datetime as dt
import json
import os
import time

import requests

from run_eval import pct, save

API = os.environ.get("SPOTME_API", "https://spotme.sites.tab.bot")
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PHOTOS = os.path.join(ROOT, "data", "sample-photos")
SELFIES = {name: os.path.join(ROOT, "web", "public", "sample", name) for name in ("behnken.jpg", "hurley-2.jpg", "glover.jpg")}


def main():
    email = f"hosted-check-{int(time.time())}@example.com"
    token = requests.post(f"{API}/api/auth/register", json={"email": email, "name": "Hosted check", "password": "password123"}).json()["token"]
    h = {"Authorization": "Bearer " + token}
    expires = (dt.datetime.now(dt.timezone.utc) + dt.timedelta(days=1)).isoformat()
    ev = requests.post(f"{API}/api/events", headers=h, json={
        "title": "Hosted check", "purposeNotice": "Measuring the live site.", "expiresAt": expires,
        "consentAttested": True}).json()["id"]

    files = sorted(f for f in os.listdir(PHOTOS) if f.endswith(".jpg"))

    def send(name):
        with open(os.path.join(PHOTOS, name), "rb") as f:
            t = time.perf_counter()
            res = requests.post(f"{API}/api/events/{ev}/photos", headers=h, files={"file": (name, f, "image/jpeg")})
            return res.status_code, (time.perf_counter() - t) * 1000

    start = time.perf_counter()
    with cf.ThreadPoolExecutor(2) as pool:
        uploads = list(pool.map(send, files))
    upload_s = time.perf_counter() - start
    assert all(status in (200, 201) for status, _ in uploads), uploads

    while True:
        c = requests.get(f"{API}/api/events/{ev}", headers=h).json()["counts"]
        if c["queued"] == 0 and c["processing"] == 0:
            break
        time.sleep(0.5)
    indexed_s = time.perf_counter() - start

    requests.post(f"{API}/api/events/{ev}/publish", headers=h)
    total, parts, counts = [], {"decode": [], "faces": [], "sql": []}, {}
    for guest in range(3):
        invite = requests.post(f"{API}/api/events/{ev}/invites", headers=h, json={"label": f"Guest {guest}"}).json()
        session = requests.post(f"{API}/api/join/{invite['token']}/consent", json={"noticeVersion": 1, "agree": True}).json()["sessionToken"]
        ah = {"Authorization": "Bearer " + session, "Content-Type": "image/jpeg"}
        for i in range(9):
            name = list(SELFIES)[i % 3]
            with open(SELFIES[name], "rb") as f:
                body = f.read()
            t = time.perf_counter()
            res = requests.post(f"{API}/api/attendee/search", headers=ah, data=body)
            ms = (time.perf_counter() - t) * 1000
            assert res.status_code == 200, res.text
            counts[name] = res.json()["count"]
            if guest == 0 and i < 3:
                continue  # warm-up
            total.append(ms)
            for part in res.headers["Server-Timing"].split(","):
                key, dur = part.strip().split(";dur=")
                parts[key].append(float(dur))

    requests.delete(f"{API}/api/events/{ev}", headers=h)

    result = {
        "api": API,
        "photos": len(files),
        "photos_ready": c["ready"],
        "upload_seconds": round(upload_s, 1),
        "upload_request_ms_p50": round(pct([ms for _, ms in uploads], 50), 1),
        "seconds_until_all_indexed": round(indexed_s, 1),
        "matches_per_selfie": counts,
        "searches": len(total),
        "search_total_ms": {"p50": round(pct(total, 50), 1), "p95": round(pct(total, 95), 1)},
        **{"search_" + k + "_ms": {"p50": round(pct(v, 50), 1), "p95": round(pct(v, 95), 1)} for k, v in parts.items()},
        "note": "total_ms is measured from this client, so it includes the network round trip; the parts come from Server-Timing.",
    }
    print(json.dumps(result, indent=1))
    save("hosted.json", result)


if __name__ == "__main__":
    main()
