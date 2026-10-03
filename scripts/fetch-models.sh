#!/usr/bin/env bash
# Downloads the two face models from the OpenCV Zoo at a pinned commit and checks their SHA-256.
# YuNet (face detection) is MIT licensed, SFace (face recognition) is Apache 2.0.
set -euo pipefail

ZOO_COMMIT=47534e27c9851bb1128ccc0102f1145e27f23f98
BASE="https://media.githubusercontent.com/media/opencv/opencv_zoo/${ZOO_COMMIT}/models"
DIR="$(cd "$(dirname "$0")/.." && pwd)/models"
mkdir -p "$DIR"

fetch() {
  local path="$1" sha="$2" file
  file="$DIR/$(basename "$path")"
  if [ -f "$file" ] && echo "$sha  $file" | sha256sum -c --status; then
    echo "ok      $(basename "$file")"
    return
  fi
  curl -fsSL -o "$file" "$BASE/$path"
  echo "$sha  $file" | sha256sum -c --status || { echo "checksum mismatch for $file"; rm -f "$file"; exit 1; }
  echo "fetched $(basename "$file")"
}

fetch face_detection_yunet/face_detection_yunet_2023mar.onnx 8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4
fetch face_recognition_sface/face_recognition_sface_2021dec.onnx 0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79
