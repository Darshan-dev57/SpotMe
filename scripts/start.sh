#!/usr/bin/env bash
# Runs SpotMe on this machine with one command:
#   PostgreSQL (Docker), the face models, the web UI build, the app itself,
#   a sample event with public domain photos and a test organizer account.
# Needs Docker, JDK 21, Maven and Node.js 20+. Safe to run again: finished steps are skipped.
# Stop with Ctrl-C.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
PORT="${PORT:-8080}"
URL="http://localhost:$PORT"
mkdir -p data
LOG="$ROOT/data/spotme.log"

say() { echo "[start] $*"; }
need() { command -v "$1" >/dev/null || { echo "Missing $1. $2"; exit 1; }; }

need docker "Install Docker: https://docs.docker.com/get-docker/"
need java "Install JDK 21."
need mvn "Install Maven 3.9+."
need npm "Install Node.js 20+."
java -version 2>&1 | grep -q 'version "2[1-9]' || { echo "SpotMe needs Java 21 or newer (java -version)."; exit 1; }
if curl -s -o /dev/null "$URL"; then
  echo "Something is already running on port $PORT. Stop it or run: PORT=8090 ./scripts/start.sh"
  exit 1
fi

say "1/6 PostgreSQL with pgvector on localhost:5434"
docker compose up -d --wait

say "2/6 face models"
./scripts/fetch-models.sh

say "3/6 web UI"
if [ ! -f web/out/index.html ] || [ -n "$(find web/src -newer web/out/index.html -print -quit)" ]; then
  (cd web && { [ -d node_modules ] || npm ci; } && npm run build)
else
  echo "already built"
fi

say "4/6 sample photos (public domain, NASA)"
if [ -z "$(ls data/sample-photos 2>/dev/null)" ]; then
  ./scripts/fetch-sample-photos.sh
else
  echo "already downloaded"
fi

say "5/6 starting the app (log: data/spotme.log)"
cd backend
SPOTME_WEB_DIR=../web/out \
SPOTME_WEB_ORIGIN="$URL" \
SPOTME_SAMPLE_ENABLED=true \
SPOTME_SAMPLE_PHOTOSDIR=../data/sample-photos \
PORT="$PORT" \
  mvn -q spring-boot:run > "$LOG" 2>&1 &
APP_PID=$!
cd "$ROOT"
trap 'say "stopping"; kill "$APP_PID" 2>/dev/null; wait "$APP_PID" 2>/dev/null; exit 0' INT TERM

for _ in $(seq 1 180); do
  curl -sf "$URL/actuator/health" >/dev/null && break
  if ! kill -0 "$APP_PID" 2>/dev/null; then
    echo "The app stopped while starting. Last lines of data/spotme.log:"
    tail -20 "$LOG"
    exit 1
  fi
  sleep 1
done
curl -sf "$URL/actuator/health" >/dev/null || { echo "The app did not start in 3 minutes, see data/spotme.log"; exit 1; }

say "6/6 test organizer account"
ACCOUNT_FILE=data/test-account.txt
EMAIL=organizer@example.com
if [ ! -s "$ACCOUNT_FILE" ]; then
  echo "$(head -c 18 /dev/urandom | base64 | tr -dc 'A-Za-z0-9')" > "$ACCOUNT_FILE"
fi
PASSWORD="$(cat "$ACCOUNT_FILE")"
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$URL/api/auth/register" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"name\":\"Test organizer\",\"password\":\"$PASSWORD\"}")
case "$code" in
  201|200) echo "created" ;;
  409) echo "already there" ;;
  *) echo "could not create it (HTTP $code), sign up in the app instead" ;;
esac

cat <<EOF

SpotMe is running at $URL

  Organizer test account   $EMAIL / $PASSWORD   (sign in at $URL/organizer)
  Sample event             on the home page, "Try it with sample photos" (indexing takes a minute)
  Log                      data/spotme.log

Press Ctrl-C to stop. The database keeps running in Docker (docker compose down to stop it).
EOF
wait "$APP_PID"
