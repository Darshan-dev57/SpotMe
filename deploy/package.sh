#!/usr/bin/env bash
# Builds everything the hosted box needs into one folder (default: deploy/out):
#   app/      the Spring Boot app, unpacked (classes + libraries)
#   web/      the static web UI (Next.js export)
#   sample-photos/  public domain NASA photos for the sample event
#   start.sh  installs Postgres + Java on the box, fetches OpenCV and the models, starts the app
#   fetch-models.sh  used by start.sh
#   tab-site.json  tells the host to run start.sh and send traffic to port 8080
# Needs JDK 21, Maven and Node 20.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-$ROOT/deploy/out}"
rm -rf "$OUT" && mkdir -p "$OUT/app"

echo "building the backend"
(cd "$ROOT/backend" && mvn -q -B package -DskipTests)
JAR="$(ls "$ROOT"/backend/target/spotme-backend-*.jar | head -1)"

# unpack the fat jar so single libraries can be changed
TMP="$(mktemp -d)"
(cd "$TMP" && unzip -q "$JAR")
mv "$TMP/BOOT-INF/classes" "$OUT/app/classes"
mv "$TMP/BOOT-INF/lib" "$OUT/app/lib"
rm -rf "$TMP"

# The two big files (OpenCV, 110 MB, and the face models, 39 MB) are not bundled:
# start.sh downloads pinned copies on the box and checks their SHA-256.
rm "$OUT"/app/lib/opencv-*.jar

echo "building the web UI"
(cd "$ROOT/web" && npm run build >/dev/null)
cp -r "$ROOT/web/out" "$OUT/web"

cp "$ROOT/scripts/fetch-models.sh" "$OUT/fetch-models.sh"

"$ROOT/scripts/fetch-sample-photos.sh" "$OUT/sample-photos" >/dev/null

cp "$ROOT/deploy/start.sh" "$OUT/start.sh"
cat > "$OUT/tab-site.json" <<'EOF'
{"kind": "server", "start": "bash start.sh", "port": 8080}
EOF

du -sh "$OUT"
