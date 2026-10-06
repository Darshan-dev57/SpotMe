"""
Threshold sweep for the scale test, run offline on this machine (not on the hosted box, which does
not expose raw distances).

  python3 eval/scale_sweep.py   # after scale_test.py prepare; reads the same manifest

It finds faces with the same two models and settings as the app (YuNet score 0.8, faces under 40 px
skipped, SFace embeddings, cosine distance) using OpenCV's Python bindings, then scores every
threshold exactly like scale_test.py does: per photo, at most 200 results, a selfie with no face or
more than one face is refused. At 0.55 it should land close to the live result; that is the check
that the copy behaves like the app.
"""
import json
import os
from multiprocessing import Pool

import cv2
import numpy as np

from run_eval import save
from scale_test import LFW, MAX_RESULTS, ROOT, load

MODELS = os.path.join(ROOT, "models")
THRESHOLDS = [0.40, 0.45, 0.50, 0.525, 0.55, 0.575, 0.60]
detector = recognizer = None


def start_worker():
    global detector, recognizer
    cv2.setNumThreads(1)
    detector = cv2.FaceDetectorYN.create(os.path.join(MODELS, "face_detection_yunet_2023mar.onnx"), "", (320, 320), 0.8, 0.3, 5000)
    recognizer = cv2.FaceRecognizerSF.create(os.path.join(MODELS, "face_recognition_sface_2021dec.onnx"), "")


def embed(name):
    image = cv2.imread(os.path.join(LFW, name))
    detector.setInputSize((image.shape[1], image.shape[0]))
    _, faces = detector.detect(image)
    vectors = []
    for face in faces if faces is not None else []:
        if face[2] < 40 or face[3] < 40:
            continue
        v = recognizer.feature(recognizer.alignCrop(image, face)).flatten()
        vectors.append(v / np.linalg.norm(v))
    return vectors


def main():
    manifest = load("manifest.json")
    event = manifest["event_photos"]
    queries = [("member", p, f) for p, f in manifest["selfies"].items()] + \
              [("stranger", p, f) for p, f in manifest["strangers"].items()]
    with Pool(3, initializer=start_worker) as pool:
        event_vectors = pool.map(embed, event, chunksize=64)
        query_vectors = pool.map(embed, [f for _, _, f in queries], chunksize=64)

    # one row per face, remembering which photo it came from
    faces, face_photo = [], []
    for photo, vectors in enumerate(event_vectors):
        faces += vectors
        face_photo += [photo] * len(vectors)
    faces, face_photo = np.array(faces), np.array(face_photo)
    photos_of = {}
    for photo, name in enumerate(event):
        photos_of.setdefault(name.split("/")[0], set()).add(photo)

    score = {t: {"returned": 0, "correct": 0, "found": 0, "strangers": 0, "stranger_hits": 0} for t in THRESHOLDS}
    truth = refused = 0
    for (kind, person, _), vectors in zip(queries, query_vectors):
        if kind == "member":
            truth += len(photos_of.get(person, ()))
        if len(vectors) != 1:
            refused += 1
            continue
        best = np.full(len(event), 9.0)
        np.minimum.at(best, face_photo, 1 - faces @ vectors[0])  # best distance per photo
        order = np.argsort(best)[:MAX_RESULTS]
        for t, s in score.items():
            got = {int(p) for p in order if best[p] <= t}
            if kind == "member":
                hits = len(got & photos_of.get(person, set()))
                s["returned"] += len(got)
                s["correct"] += hits
                s["found"] += hits > 0
            else:
                s["strangers"] += 1
                s["stranger_hits"] += bool(got)

    rows = [{"threshold": t, "precision": round(s["correct"] / s["returned"], 4), "recall": round(s["correct"] / truth, 4),
             "photos_wrong": s["returned"] - s["correct"], "strangers_with_any_result": s["stranger_hits"],
             "stranger_false_match_rate": round(s["stranger_hits"] / s["strangers"], 4)} for t, s in score.items()]
    result = {"what": "offline threshold sweep on the scale-test split, same models and settings as the app",
              "event_photos": len(event), "event_faces": len(faces), "queries": len(queries),
              "refused_selfies": refused, "strangers_answered": score[THRESHOLDS[0]]["strangers"], "thresholds": rows}
    print(json.dumps(result, indent=1))
    save("scale_sweep.json", result)


if __name__ == "__main__":
    main()
