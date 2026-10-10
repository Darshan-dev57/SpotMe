"""Peak memory and time of each erase model on its own, in a fresh process.
Usage: python eval/erase_memory.py MODELS_DIR PHOTO MASK STAGE THREADS
STAGE is one of: baseline, people, outline, lama, migan."""
import resource
import sys
import time

import cv2
import numpy as np
import onnxruntime as ort


def peak_mb():
    return resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024


def session(path, threads):
    opts = ort.SessionOptions()
    opts.intra_op_num_threads = threads
    opts.inter_op_num_threads = 1
    opts.enable_cpu_mem_arena = False      # smaller peak, a little slower
    opts.enable_mem_pattern = False
    return ort.InferenceSession(path, opts, providers=["CPUExecutionProvider"])


models, photo, mask_path, stage, threads = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4], int(sys.argv[5])
img = cv2.imread(photo)
s = min(1.0, 1600 / max(img.shape[:2]))
img = cv2.resize(img, None, fx=s, fy=s, interpolation=cv2.INTER_AREA)
mask = cv2.imread(mask_path, 0)
base = peak_mb()
t0 = time.perf_counter()
if stage == "people":
    sess = session(f"{models}/object_detection_yolox_2022nov.onnx", threads)
    t1 = time.perf_counter()
    sess.run(None, {"images": np.zeros((1, 3, 640, 640), np.float32)})
elif stage == "outline":
    enc = session(f"{models}/mobile_sam_image_encoder.onnx", threads)
    dec = session(f"{models}/sam_mask_decoder_multi.onnx", threads)
    t1 = time.perf_counter()
    h, w = img.shape[:2]
    k = 1024 / max(h, w)
    emb = enc.run(None, {"input_image": cv2.resize(img, (int(w * k + 0.5), int(h * k + 0.5))).astype(np.float32)})[0]
    dec.run(None, {"image_embeddings": emb, "point_coords": np.array([[[10, 10], [500, 500], [200, 200]]], np.float32),
                   "point_labels": np.array([[2, 3, 1]], np.float32), "mask_input": np.zeros((1, 1, 256, 256), np.float32),
                   "has_mask_input": np.zeros(1, np.float32), "orig_im_size": np.array([h, w], np.float32)})
elif stage == "lama":
    sess = session(f"{models}/lama_fp32.onnx", threads)
    t1 = time.perf_counter()
    sess.run(None, {"image": np.random.rand(1, 3, 512, 512).astype(np.float32),
                    "mask": (np.random.rand(1, 1, 512, 512) > 0.7).astype(np.float32)})
elif stage == "migan":
    sess = session(f"{models}/migan_pipeline_v2.onnx", threads)
    t1 = time.perf_counter()
    rgb = cv2.cvtColor(img, cv2.COLOR_BGR2RGB).transpose(2, 0, 1)[None]
    sess.run(None, {"image": rgb, "mask": np.where(mask > 127, 0, 255).astype(np.uint8)[None, None]})
else:
    t1 = time.perf_counter()
t2 = time.perf_counter()
print(f'{{"stage": "{stage}", "threads": {threads}, "baseline_mb": {base:.1f}, "peak_mb": {peak_mb():.1f}, '
      f'"added_mb": {peak_mb() - base:.1f}, "load_s": {t1 - t0:.2f}, "run_s": {t2 - t1:.2f}}}')
