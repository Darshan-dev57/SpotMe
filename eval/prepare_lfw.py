"""
Builds the SpotMe evaluation set from LFW (Labeled Faces in the Wild).

LFW is a public research benchmark of news photos of public figures
(http://vis-www.cs.umass.edu/lfw/). It is only used here to measure the matching
pipeline; none of its images are committed to the repository.

Output (all under eval/data/, which git ignores):
  event/      composite "event photos", 1 to 4 faces each, 1600x1067 JPEG
  queries/    one held-out selfie per person (present and absent people)
  manifest.json  ground truth: which people are in which event photo, and the splits

Usage:  python3 eval/prepare_lfw.py [path/to/lfw.tgz]
"""
import json
import os
import random
import sys
import tarfile
import urllib.request
from collections import defaultdict

from PIL import Image

SEED = 7
HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(HERE, "data")
LFW_URL = "https://ndownloader.figshare.com/files/5976018"  # lfw.tgz, the mirror scikit-learn uses

PRESENT = 100        # people who are in the event (half tune, half test)
PHOTOS_EACH = 5      # event appearances per present person (plus 1 held-out query)
ABSENT = 50          # people who are NOT in the event but still search (negative queries)
DISTRACTORS = 300    # other guests with a single image
CANVAS = (1600, 1067)
TILE = 360           # each face tile is a 150px centre crop of LFW scaled to 360px


def main():
    tgz = sys.argv[1] if len(sys.argv) > 1 else os.path.join(DATA, "lfw.tgz")
    if not os.path.exists(tgz):
        os.makedirs(DATA, exist_ok=True)
        print("downloading LFW (~180 MB) ...")
        urllib.request.urlretrieve(LFW_URL, tgz)

    rng = random.Random(SEED)
    tar = tarfile.open(tgz, "r:gz")
    members = {m.name: m for m in tar.getmembers() if m.name.endswith(".jpg")}
    by_person = defaultdict(list)
    for name in sorted(members):
        by_person[name.split("/")[1]].append(name)

    many = sorted(p for p, imgs in by_person.items() if len(imgs) >= PHOTOS_EACH + 1)
    some = sorted(p for p, imgs in by_person.items() if 2 <= len(imgs) <= PHOTOS_EACH)
    single = sorted(p for p, imgs in by_person.items() if len(imgs) == 1)

    present = rng.sample(many, PRESENT)
    absent = rng.sample(some, ABSENT)
    distractors = rng.sample(single, DISTRACTORS)

    def load(name):
        return Image.open(tar.extractfile(members[name])).convert("RGB")

    os.makedirs(os.path.join(DATA, "event"), exist_ok=True)
    os.makedirs(os.path.join(DATA, "queries"), exist_ok=True)

    queries = []
    tiles = []  # (person, image name)
    for i, person in enumerate(present):
        imgs = by_person[person][:]
        rng.shuffle(imgs)
        queries.append({"person": person, "file": f"q_{person}.jpg", "present": True,
                        "split": "tune" if i % 2 == 0 else "test"})
        load(imgs[0]).save(os.path.join(DATA, "queries", f"q_{person}.jpg"), quality=92)
        for name in imgs[1:PHOTOS_EACH + 1]:
            tiles.append((person, name))
    for i, person in enumerate(absent):
        queries.append({"person": person, "file": f"q_{person}.jpg", "present": False,
                        "split": "tune" if i % 2 == 0 else "test"})
        load(by_person[person][0]).save(os.path.join(DATA, "queries", f"q_{person}.jpg"), quality=92)
    for person in distractors:
        tiles.append((person, by_person[person][0]))

    # pack the face tiles into event photos: 55% solo, the rest groups of 2-4
    rng.shuffle(tiles)
    photos = []
    i = 0
    while i < len(tiles):
        n = 1 if rng.random() < 0.55 else rng.randint(2, 4)
        group = tiles[i:i + n]
        i += n
        canvas = Image.new("RGB", CANVAS, tuple(rng.randint(60, 200) for _ in range(3)))
        slot_w = CANVAS[0] // len(group)
        people = []
        for k, (person, name) in enumerate(group):
            img = load(name)
            face = img.crop((50, 50, 200, 200)).resize((TILE, TILE), Image.BICUBIC)
            x = k * slot_w + (slot_w - TILE) // 2 + rng.randint(-20, 20)
            y = (CANVAS[1] - TILE) // 2 + rng.randint(-150, 150)
            canvas.paste(face, (max(0, x), y))
            people.append(person)
        fname = f"event_{len(photos):04d}.jpg"
        canvas.save(os.path.join(DATA, "event", fname), quality=90)
        photos.append({"file": fname, "people": people})

    manifest = {"source": "LFW, http://vis-www.cs.umass.edu/lfw/", "seed": SEED,
                "photos": photos, "queries": queries,
                "counts": {"event_photos": len(photos), "face_tiles": len(tiles),
                           "present_people": PRESENT, "absent_people": ABSENT, "distractors": DISTRACTORS}}
    with open(os.path.join(DATA, "manifest.json"), "w") as f:
        json.dump(manifest, f, indent=1)
    print(json.dumps(manifest["counts"]))


if __name__ == "__main__":
    main()
