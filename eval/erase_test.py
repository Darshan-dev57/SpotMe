"""Feasibility test for removing one person from a group photo.

Not part of the app. It answers one question before anything is built: can free models that run
on our own machine remove a guest from a group photo so that the result still looks natural, and
how much memory and time does that take?

Steps for one photo:
  1. YuNet finds the faces (the app already does this). The target face is given by its index.
  2. YOLOX (OpenCV Zoo) finds person boxes. The box that holds the target face is the target body.
  3. MobileSAM cuts out the exact outline of that person, prompted with the body box, a point on
     the face and negative points on the other faces inside the box.
  4. The mask is grown a little and an inpainting model (LaMa or MI-GAN) fills it from the
     surrounding background.

Usage:
  python eval/erase_test.py --models DIR --photos DIR --out DIR --plan plan.json [--inpaint lama|migan]
plan.json maps a photo file name to {"face": index of the face to remove, faces sorted left to right}.
ERASE_GROW_MIN, ERASE_GROW_FACE and ERASE_CONTEXT override the mask growth and crop size for tuning.

Models (all free): face_detection_yunet_2023mar.onnx and object_detection_yolox_2022nov.onnx from the
OpenCV Zoo, mobile_sam_image_encoder.onnx and sam_mask_decoder_multi.onnx from Acly/MobileSAM,
lama_fp32.onnx from Carve/LaMa-ONNX and migan_pipeline_v2.onnx from andraniksargsyan/migan
(Hugging Face).
"""
import argparse
import json
import os
import resource
import time

import cv2
import numpy as np
import onnxruntime as ort

WORK_SIDE = 1600   # photos are processed at this long side at most
GROW_MIN = int(os.environ.get('ERASE_GROW_MIN', '14'))        # the mask edge is grown by at least this many pixels
GROW_FACE = float(os.environ.get('ERASE_GROW_FACE', '0.15'))  # ... or by this share of the face height, whichever is larger
CONTEXT = float(os.environ.get('ERASE_CONTEXT', '2.0'))  # crop side as a multiple of the person's size


def rss_peak_mb():
    return resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024


def session(path, threads):
    opts = ort.SessionOptions()
    opts.intra_op_num_threads = threads
    opts.inter_op_num_threads = 1
    return ort.InferenceSession(path, opts, providers=["CPUExecutionProvider"])


def load_photo(path):
    img = cv2.imread(path)
    scale = min(1.0, WORK_SIDE / max(img.shape[:2]))
    if scale < 1.0:
        img = cv2.resize(img, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA)
    return img


def detect_faces(models, img):
    h, w = img.shape[:2]
    det = cv2.FaceDetectorYN.create(os.path.join(models, "face_detection_yunet_2023mar.onnx"), "", (w, h), 0.6)
    _, faces = det.detect(img)
    if faces is None:
        return []
    faces = [f[:4].astype(float) for f in faces if f[2] >= 12]
    return sorted(faces, key=lambda f: f[0])


def detect_people(sess, img):
    """YOLOX-S from the OpenCV Zoo; returns person boxes as x1, y1, x2, y2."""
    h, w = img.shape[:2]
    ratio = min(640 / h, 640 / w)
    padded = np.full((640, 640, 3), 114, np.float32)
    padded[:int(h * ratio), :int(w * ratio)] = cv2.resize(img, (int(w * ratio), int(h * ratio)))
    out = sess.run(None, {"images": padded.transpose(2, 0, 1)[None]})[0][0]
    grids, strides = [], []
    for s in (8, 16, 32):
        n = 640 // s
        xv, yv = np.meshgrid(np.arange(n), np.arange(n))
        grids.append(np.stack((xv, yv), 2).reshape(-1, 2))
        strides.append(np.full((n * n, 1), s))
    grids, strides = np.concatenate(grids), np.concatenate(strides)
    xy = (out[:, :2] + grids) * strides
    wh = np.exp(out[:, 2:4]) * strides
    score = out[:, 4] * out[:, 5]          # objectness x "person" class
    keep = score > 0.3
    boxes = np.concatenate([xy - wh / 2, xy + wh / 2], 1)[keep] / ratio
    score = score[keep]
    idx = cv2.dnn.NMSBoxes([[b[0], b[1], b[2] - b[0], b[3] - b[1]] for b in boxes], score.tolist(), 0.3, 0.5)
    return [boxes[i] for i in np.array(idx).flatten()]


def body_box(face, people, img_shape):
    """Pick the person box that holds the face near its top; fall back to a box built from the face."""
    fx, fy, fw, fh = face
    cx, cy = fx + fw / 2, fy + fh / 2
    best = None
    for b in people:
        x1, y1, x2, y2 = b
        if x1 <= cx <= x2 and y1 - fh * 0.3 <= cy <= y1 + (y2 - y1) * 0.4:
            width_ok = (x2 - x1) < fw * 7
            if width_ok and (best is None or (x2 - x1) * (y2 - y1) < (best[2] - best[0]) * (best[3] - best[1])):
                best = b
    h, w = img_shape[:2]
    if best is None:
        best = np.array([cx - fw * 1.8, fy - fh * 0.4, cx + fw * 1.8, min(h, fy + fh * 8)])
    x1, y1, x2, y2 = best
    return np.array([max(0, x1), max(0, min(y1, fy - fh * 0.3)), min(w, x2), min(h, y2)])


class Sam:
    def __init__(self, models, threads):
        self.enc = session(os.path.join(models, "mobile_sam_image_encoder.onnx"), threads)
        self.dec = session(os.path.join(models, "sam_mask_decoder_multi.onnx"), threads)

    def person_mask(self, img, box, face, other_faces):
        h, w = img.shape[:2]
        s = 1024 / max(h, w)
        rgb = cv2.cvtColor(cv2.resize(img, (int(w * s + 0.5), int(h * s + 0.5))), cv2.COLOR_BGR2RGB)
        emb = self.enc.run(None, {"input_image": rgb.astype(np.float32)})[0]
        fx, fy, fw, fh = face
        pts = [[box[0], box[1]], [box[2], box[3]], [fx + fw / 2, fy + fh / 2]]
        labels = [2, 3, 1]
        torso = [fx + fw / 2, fy + fh * 2.2]
        if torso[1] < box[3]:
            pts.append(torso)
            labels.append(1)
        for ox, oy, ow, oh in other_faces:
            c = [ox + ow / 2, oy + oh / 2]
            if box[0] <= c[0] <= box[2] and box[1] <= c[1] <= box[3]:
                pts.append(c)
                labels.append(0)
        coords = (np.array(pts, np.float32) * s)[None]
        masks, iou, _ = self.dec.run(None, {
            "image_embeddings": emb,
            "point_coords": coords,
            "point_labels": np.array(labels, np.float32)[None],
            "mask_input": np.zeros((1, 1, 256, 256), np.float32),
            "has_mask_input": np.zeros(1, np.float32),
            "orig_im_size": np.array([h, w], np.float32),
        })
        best = int(np.argmax(iou[0]))
        return (masks[0, best] > 0).astype(np.uint8) * 255


def clean_mask(mask, face, other_faces):
    """Keep the parts joined to the face, fill holes and grow the edge so no outline is left.
    The grown edge never covers another person's face."""
    fx, fy, fw, fh = face
    n, lab, stats, _ = cv2.connectedComponentsWithStats(mask)
    keep = np.zeros_like(mask)
    face_box = lab[int(fy):int(fy + fh), int(fx):int(fx + fw)]
    ids = set(np.unique(face_box)) - {0}
    for i in range(1, n):
        if i in ids or stats[i, cv2.CC_STAT_AREA] > 0.15 * mask.sum() / 255:
            keep[lab == i] = 255
    # fill holes
    inv = cv2.bitwise_not(keep)
    n2, lab2, stats2, _ = cv2.connectedComponentsWithStats(inv)
    for i in range(1, n2):
        x, y, w, h, a = stats2[i]
        if x > 0 and y > 0 and x + w < mask.shape[1] and y + h < mask.shape[0]:
            keep[lab2 == i] = 255
    grow = max(GROW_MIN, int(fh * GROW_FACE))
    k = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (grow * 2 + 1, grow * 2 + 1))
    grown = cv2.dilate(keep, k)
    for ox, oy, ow, oh in other_faces:
        x1, y1 = int(ox - ow * 0.1), int(oy - oh * 0.2)
        x2, y2 = int(ox + ow * 1.1), int(oy + oh * 1.1)
        region = grown[max(0, y1):y2, max(0, x1):x2]
        region[:] = keep[max(0, y1):y2, max(0, x1):x2]
    return grown


class Lama:
    def __init__(self, models, threads):
        self.sess = session(os.path.join(models, "lama_fp32.onnx"), threads)

    def fill(self, img, mask):
        h, w = img.shape[:2]
        ys, xs = np.nonzero(mask)
        x1, x2, y1, y2 = xs.min(), xs.max(), ys.min(), ys.max()
        # the crop around the person: more background around the hole gives a cleaner fill
        side = int(max(x2 - x1, y2 - y1) * CONTEXT) + 32
        sw, sh = min(w, max(side, 512)), min(h, max(side, 512))
        cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
        sx = int(np.clip(cx - sw // 2, 0, w - sw))
        sy = int(np.clip(cy - sh // 2, 0, h - sh))
        crop = img[sy:sy + sh, sx:sx + sw]
        cm = mask[sy:sy + sh, sx:sx + sw]
        ch, cw = crop.shape[:2]
        # the model takes exactly 512 x 512; a crop that is not square is stretched and stretched back
        a = cv2.resize(cv2.cvtColor(crop, cv2.COLOR_BGR2RGB), (512, 512), interpolation=cv2.INTER_AREA)
        m = cv2.resize(cm, (512, 512), interpolation=cv2.INTER_NEAREST)
        out = self.sess.run(None, {
            "image": (a.astype(np.float32) / 255).transpose(2, 0, 1)[None],
            "mask": (m > 127).astype(np.float32)[None, None],
        })[0][0].transpose(1, 2, 0)
        out = cv2.cvtColor(np.clip(out, 0, 255).astype(np.uint8), cv2.COLOR_RGB2BGR)
        out = cv2.resize(out, (cw, ch), interpolation=cv2.INTER_CUBIC)
        result = img.copy()
        result[sy:sy + ch, sx:sx + cw] = blend(crop, out, cm)
        return result


class Migan:
    def __init__(self, models, threads):
        self.sess = session(os.path.join(models, "migan_pipeline_v2.onnx"), threads)

    def fill(self, img, mask):
        rgb = cv2.cvtColor(img, cv2.COLOR_BGR2RGB).transpose(2, 0, 1)[None]
        keep = np.where(mask > 127, 0, 255).astype(np.uint8)[None, None]
        out = self.sess.run(None, {"image": rgb, "mask": keep})[0][0].transpose(1, 2, 0)
        out = cv2.cvtColor(out.astype(np.uint8), cv2.COLOR_RGB2BGR)
        return blend(img, out, mask)


def blend(orig, filled, mask):
    """Use the filled pixels inside the mask with a soft edge, the original everywhere else."""
    soft = cv2.GaussianBlur(mask, (0, 0), 3).astype(np.float32)[..., None] / 255
    soft = np.maximum(soft, (mask > 127)[..., None].astype(np.float32))
    return (filled * soft + orig * (1 - soft)).astype(np.uint8)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--models", required=True)
    ap.add_argument("--photos", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--plan", required=True)
    ap.add_argument("--inpaint", default="lama", choices=["lama", "migan"])
    ap.add_argument("--threads", type=int, default=2)
    ap.add_argument("--only", default="")
    args = ap.parse_args()
    plan = json.load(open(args.plan))
    os.makedirs(args.out, exist_ok=True)

    t = time.perf_counter()
    yolo = session(os.path.join(args.models, "object_detection_yolox_2022nov.onnx"), args.threads)
    sam = Sam(args.models, args.threads)
    filler = Lama(args.models, args.threads) if args.inpaint == "lama" else Migan(args.models, args.threads)
    load_s = time.perf_counter() - t
    rows = []
    for name, target in sorted(plan.items()):
        if args.only and name not in args.only.split(","):
            continue
        img = load_photo(os.path.join(args.photos, name))
        times = {}
        t = time.perf_counter(); faces = detect_faces(args.models, img); times["faces"] = time.perf_counter() - t
        face = faces[target["face"]]
        others = [f for i, f in enumerate(faces) if i != target["face"]]
        t = time.perf_counter(); people = detect_people(yolo, img); times["people"] = time.perf_counter() - t
        box = body_box(face, people, img.shape)
        t = time.perf_counter(); raw = sam.person_mask(img, box, face, others); times["outline"] = time.perf_counter() - t
        mask = clean_mask(raw, face, others)
        t = time.perf_counter(); result = filler.fill(img, mask); times["fill"] = time.perf_counter() - t
        stem = os.path.splitext(name)[0]
        cv2.imwrite(os.path.join(args.out, f"{stem}_after_{args.inpaint}.jpg"), result, [cv2.IMWRITE_JPEG_QUALITY, 90])
        cv2.imwrite(os.path.join(args.out, f"{stem}_mask.png"), mask)
        dbg = img.copy()
        dbg[mask > 0] = (dbg[mask > 0] * 0.4 + np.array([0, 0, 255]) * 0.6).astype(np.uint8)
        cv2.rectangle(dbg, (int(box[0]), int(box[1])), (int(box[2]), int(box[3])), (0, 255, 0), 3)
        cv2.imwrite(os.path.join(args.out, f"{stem}_mask_overlay.jpg"), dbg, [cv2.IMWRITE_JPEG_QUALITY, 85])
        row = {"photo": name, "size": [img.shape[1], img.shape[0]], "faces": len(faces), "people": len(people),
               "mask_share": round(float((mask > 0).mean()), 4),
               "seconds": {k: round(v, 3) for k, v in times.items()},
               "total_seconds": round(sum(times.values()), 3), "rss_peak_mb": round(rss_peak_mb(), 1)}
        rows.append(row)
        print(json.dumps(row), flush=True)
    json.dump({"inpaint": args.inpaint, "threads": args.threads, "model_load_seconds": round(load_s, 2),
               "rss_peak_mb": round(rss_peak_mb(), 1), "photos": rows},
              open(os.path.join(args.out, f"run_{args.inpaint}.json"), "w"), indent=1)


if __name__ == "__main__":
    main()
