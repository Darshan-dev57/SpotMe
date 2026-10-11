"""
"Find one person's photos in a big event": every person with 10 or more photos in the scale-test
event searches for themselves on the hosted app, and we count how many of their photos come back.

  python3 eval/scale_person.py   # after scale_test.py upload; needs the same manifest, state and uploads log

Uses the scale-test event (11,251 photos) and the same held-out selfies (seed 11). A selfie the API refuses
because it has more than one face is retried with the person's first event photo that has exactly one face;
that photo is then left out of their count. Four people are also searched again with a clear front photo
picked by eye after looking at their misses (CLEAR_RETRY). Each guest does at most 9 searches, under the
10 per minute limit. The 0.50 column is an offline estimate with the same models as the app (scale_sweep.py),
because the hosted box does not expose distances.
"""
import collections
import json
import os
import statistics
import time

import numpy as np
import requests

from run_eval import save
from scale_test import API, LFW, LOGS, MAX_RESULTS, load, organizer, read_lines

MIN_PHOTOS = 10
PER_GUEST = 9
CLEAR_RETRY = {
    "David_Beckham": "David_Beckham/David_Beckham_0001.jpg",
    "Anna_Kournikova": "Anna_Kournikova/Anna_Kournikova_0012.jpg",
    "Roger_Federer": "Roger_Federer/Roger_Federer_0007.jpg",
    "Naomi_Watts": "Naomi_Watts/Naomi_Watts_0002.jpg",
}


def offline_vectors(manifest):
    """Face vectors for every file, from scale_sweep's embed (cached in the logs folder)."""
    vec_path, key_path = os.path.join(LOGS, "vecs.npy"), os.path.join(LOGS, "keys.json")
    if not os.path.exists(vec_path):
        from multiprocessing import Pool
        import scale_sweep
        names = manifest["event_photos"] + list(manifest["selfies"].values()) + list(manifest["strangers"].values())
        with Pool(3, initializer=scale_sweep.start_worker) as pool:
            found = pool.map(scale_sweep.embed, names, chunksize=64)
        keys = [n for n, vs in zip(names, found) for _ in vs]
        np.save(vec_path, np.array([v for vs in found for v in vs], dtype=np.float32))
        with open(key_path, "w") as f:
            json.dump(keys, f)
    vectors = np.load(vec_path)
    with open(key_path) as f:
        keys = json.load(f)
    by_file = {}
    for key, v in zip(keys, vectors):
        by_file.setdefault(key, []).append(v)
    return by_file


class Guests:
    """Hands out guest sessions, a new one every PER_GUEST searches."""

    def __init__(self, event_id):
        self.event_id, self.session, self.used = event_id, None, PER_GUEST

    def search(self, name):
        if self.used >= PER_GUEST:
            invite = requests.post(f"{API}/api/events/{self.event_id}/invites", headers=organizer(),
                                   json={"label": "person test"}, timeout=30).json()
            self.session = requests.post(f"{API}/api/join/{invite['token']}/consent",
                                         json={"noticeVersion": 1, "agree": True}, timeout=30).json()["sessionToken"]
            self.used = 0
        self.used += 1
        with open(os.path.join(LFW, name), "rb") as f:
            body = f.read()
        t = time.perf_counter()
        res = requests.post(f"{API}/api/attendee/search", data=body, timeout=60,
                            headers={"Authorization": "Bearer " + self.session, "Content-Type": "image/jpeg"})
        row = {"selfie": name, "status": res.status_code, "ms": round((time.perf_counter() - t) * 1000, 1),
               "timing": res.headers.get("Server-Timing")}
        body = res.json()
        if res.status_code == 200:
            row["photos"] = [p["id"] for p in body["photos"]]
        else:
            row["error"] = body.get("code")
        return row


def score(truth, returned, skip=None):
    """Counts for one person; skip is the photo used as the selfie, if it came from the event."""
    truth = truth - {skip}
    returned = [p for p in returned if p != skip]
    own_ranks = [i + 1 for i, p in enumerate(returned) if p in truth]
    wrong_ranks = [i + 1 for i, p in enumerate(returned) if p not in truth]
    return {"photos_in_event": len(truth), "found": len(own_ranks), "wrong": len(wrong_ranks),
            "found_share": round(len(own_ranks) / len(truth), 4),
            "own_ranks": own_ranks, "wrong_ranks": wrong_ranks,
            "all_own_before_wrong": not own_ranks or not wrong_ranks or max(own_ranks) < min(wrong_ranks),
            "missed": sorted(truth - set(returned))}


def offline_search(faces, face_photo, photo_ids, query, threshold, skip=None):
    best = np.full(len(photo_ids), 9.0)
    np.minimum.at(best, face_photo, 1 - faces @ query)
    if skip is not None:
        best[photo_ids.index(skip)] = 9.0
    return [photo_ids[i] for i in np.argsort(best)[:MAX_RESULTS] if best[i] <= threshold]


def stats(rows):
    shares = [r["found_share"] for r in rows]
    return {"people": len(rows), "photos_in_event": sum(r["photos_in_event"] for r in rows),
            "found": sum(r["found"] for r in rows), "median_found_share": statistics.median(shares),
            "mean_found_share": round(statistics.mean(shares), 4),
            "people_100": sum(s == 1 for s in shares), "people_90_plus": sum(s >= 0.9 for s in shares),
            "people_under_50": sum(s < 0.5 for s in shares),
            "wrong_photos": sum(r["wrong"] for r in rows), "people_with_any_wrong": sum(r["wrong"] > 0 for r in rows),
            "people_all_own_before_wrong": sum(r["all_own_before_wrong"] for r in rows),
            "found_share_buckets": dict(collections.Counter(
                "100%" if s == 1 else "90-99%" if s >= 0.9 else "75-89%" if s >= 0.75 else "50-74%" if s >= 0.5 else "under 50%"
                for s in shares))}


def pct(values, p):
    values = sorted(values)
    return values[min(len(values) - 1, int(round(p / 100 * (len(values) - 1))))]


def main():
    manifest, state = load("manifest.json"), load("state.json")
    photo_of_file = {u["file"]: u["photo"] for u in read_lines("uploads.jsonl") if u.get("photo")}
    photos_of = collections.defaultdict(set)
    for name, photo in photo_of_file.items():
        photos_of[name.split("/")[0]].add(photo)
    counts = collections.Counter(n.split("/")[0] for n in manifest["event_photos"])
    people = sorted((p for p in manifest["selfies"] if counts[p] >= MIN_PHOTOS), key=lambda p: (-counts[p], p))
    vectors = offline_vectors(manifest)

    guests = Guests(state["event"])
    rows, searches = [], []
    for person in people:
        first = guests.search(manifest["selfies"][person])
        first["person"], first["kind"] = person, "held_out"
        searches.append(first)
        row = {"person": person, "selfie": first["selfie"], "selfie_status": first["status"]}
        used, skip = first, None
        if first["status"] != 200:
            row["selfie_error"] = first.get("error")
            other = sorted(n for n in manifest["event_photos"] if n.startswith(person + "/") and len(vectors.get(n, [])) == 1)[0]
            used = guests.search(other)
            used["person"], used["kind"] = person, "other_photo"
            searches.append(used)
            row["other_selfie"], skip = other, photo_of_file[other]
        row["selfie_used"] = used["selfie"]
        row["search_ms"] = used["ms"]
        row.update(score(photos_of[person], used.get("photos", []), skip))
        rows.append(row)

    retries = []
    for person, name in CLEAR_RETRY.items():
        r = guests.search(name)
        r["person"], r["kind"] = person, "clear_retry"
        searches.append(r)
        retries.append({"person": person, "selfie": name, **score(photos_of[person], r.get("photos", []), photo_of_file[name])})

    # offline copy of the same searches at 0.55 (check) and 0.50 (estimate)
    photo_ids = sorted(set(photo_of_file.values()))
    index = {p: i for i, p in enumerate(photo_ids)}
    faces, face_photo = [], []
    for name in manifest["event_photos"]:
        for v in vectors.get(name, []):
            faces.append(v)
            face_photo.append(index[photo_of_file[name]])
    faces, face_photo = np.array(faces), np.array(face_photo)
    offline = {}
    for t in (0.55, 0.50):
        out = []
        for row in rows:
            skip = photo_of_file.get(row.get("other_selfie"))
            got = offline_search(faces, face_photo, photo_ids, vectors[row["selfie_used"]][0], t, skip)
            out.append({"person": row["person"], **score(photos_of[row["person"]], got, skip)})
        offline[str(t)] = {"summary": stats(out),
                           "per_person": [{k: r[k] for k in ("person", "photos_in_event", "found", "wrong")} for r in out]}

    answered = [s["ms"] for s in searches if s["status"] == 200]
    small = [r for r in rows if r["photos_in_event"] <= 15]
    result = {
        "what": "each person with 10+ photos in the 11,251-photo scale-test event searches for themselves on the hosted app",
        "api": API, "event": state["event"], "threshold": 0.55, "max_results": MAX_RESULTS,
        "measured_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "people": len(people), "refused_selfies": sum(r["selfie_status"] != 200 for r in rows),
        "refused_reasons": dict(collections.Counter(r.get("selfie_error") for r in rows if r["selfie_status"] != 200)),
        "live": stats(rows), "live_10_to_15_photos": stats(small),
        "search_ms": {"searches": len(answered), "p50": pct(answered, 50), "p95": pct(answered, 95), "max": max(answered)},
        "offline": {"note": "same models and settings as the app, run on the development machine; 0.50 is an estimate, not live", **offline},
        "clear_selfie_retry": retries,
        "per_person": rows,
    }
    save("scale_person.json", result)
    with open(os.path.join(LOGS, "person_searches.jsonl"), "w") as f:
        for s in searches:
            f.write(json.dumps(s) + "\n")
    print(json.dumps({k: result[k] for k in ("people", "refused_selfies", "live", "live_10_to_15_photos", "search_ms")}, indent=1))
    print(json.dumps({t: o["summary"] for t, o in offline.items()}, indent=1))


if __name__ == "__main__":
    main()
