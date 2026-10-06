#!/usr/bin/env bash
# Starts SpotMe on a fresh Debian 12 box with nothing installed: PostgreSQL 16 + pgvector,
# a Java 21 runtime, OpenCV and the face models, then the app itself (API, job worker and
# web UI in one process).
# Safe to run again: every step checks what is already there first.
#
# The box is small (about 470 MB of memory, 2 CPUs), so Postgres, the JVM and the face
# engine are all sized down, and a swap file absorbs short peaks.
set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")" && pwd)"
DATA_DIR="${SPOTME_DATA_DIR:-$HOME/spotme-data}"
RUNTIME_DIR="${SPOTME_RUNTIME_DIR:-$HOME/spotme-runtime}"
PORT="${PORT:-8080}"
PG_VERSION=16
mkdir -p "$DATA_DIR/tmp" "$RUNTIME_DIR"

log() { echo "[start] $*"; }

# 1. Swap: a 1 GB file, so a large photo decode does not get the process killed.
if [ "$(swapon --show --noheadings | wc -l)" -eq 0 ]; then
  log "adding 1 GB swap file"
  if [ ! -f /swapfile ]; then
    sudo fallocate -l 1G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile >/dev/null
  fi
  sudo swapon /swapfile || log "swap not available, continuing without it"
fi

# 2. PostgreSQL 16 with pgvector, from the PostgreSQL project's own apt repository.
if [ ! -x "/usr/lib/postgresql/$PG_VERSION/bin/postgres" ] || [ ! -f "/usr/share/postgresql/$PG_VERSION/extension/vector.control" ]; then
  log "installing PostgreSQL $PG_VERSION and pgvector"
  sudo install -d /usr/share/postgresql-common/pgdg
  sudo curl -fsSL -o /usr/share/postgresql-common/pgdg/apt.postgresql.org.asc https://www.postgresql.org/media/keys/ACCC4CF8.asc
  echo "deb [signed-by=/usr/share/postgresql-common/pgdg/apt.postgresql.org.asc] https://apt.postgresql.org/pub/repos/apt bookworm-pgdg main" \
    | sudo tee /etc/apt/sources.list.d/pgdg.list >/dev/null
  sudo apt-get update -qq
  sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq --no-install-recommends \
    "postgresql-$PG_VERSION" "postgresql-$PG_VERSION-pgvector" >/dev/null
fi

if ! pg_lsclusters --no-header 2>/dev/null | grep -q "^$PG_VERSION *main"; then
  sudo pg_createcluster "$PG_VERSION" main >/dev/null
fi

# small memory settings for a small box (Debian reads every file in conf.d)
sudo tee "/etc/postgresql/$PG_VERSION/main/conf.d/spotme.conf" >/dev/null <<'EOF'
shared_buffers = 32MB
max_connections = 20
work_mem = 4MB
maintenance_work_mem = 32MB
effective_cache_size = 128MB
listen_addresses = 'localhost'
EOF
sudo pg_ctlcluster "$PG_VERSION" main restart

# database user with a random password made on the first start and kept in the data folder
if [ ! -s "$DATA_DIR/db-password" ]; then
  head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' > "$DATA_DIR/db-password"
  chmod 600 "$DATA_DIR/db-password"
fi
DB_PASSWORD="$(cat "$DATA_DIR/db-password")"
sudo -u postgres psql -q -v ON_ERROR_STOP=1 <<EOF
DO \$\$ BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'spotme') THEN CREATE ROLE spotme LOGIN; END IF;
END \$\$;
ALTER ROLE spotme PASSWORD '$DB_PASSWORD';
EOF
if ! sudo -u postgres psql -tAc "SELECT 1 FROM pg_database WHERE datname = 'spotme'" | grep -q 1; then
  sudo -u postgres createdb -O spotme spotme
fi
# creating an extension needs a superuser, so it is done here and not by the app's migration
sudo -u postgres psql -q -d spotme -c "CREATE EXTENSION IF NOT EXISTS vector"

# 3. Java 21 runtime (Eclipse Temurin JRE), downloaded once per box.
JAVA="$RUNTIME_DIR/jre/bin/java"
if [ ! -x "$JAVA" ]; then
  log "downloading a Java 21 runtime"
  rm -rf "$RUNTIME_DIR/jre" && mkdir -p "$RUNTIME_DIR/jre"
  curl -fsSL "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jre/hotspot/normal/eclipse" \
    | tar -xz -C "$RUNTIME_DIR/jre" --strip-components=1
fi

# 4. Files too big to bundle, fetched once per box from their original sources and checked.
OPENCV_JAR="$RUNTIME_DIR/lib/opencv-4.9.0-0.jar"
OPENCV_SHA256=17823d249cb8ba18bc3cdb878db84936694d1cfaeac0f95517e320c8a8c458d9
if ! echo "$OPENCV_SHA256  $OPENCV_JAR" | sha256sum -c --status 2>/dev/null; then
  log "downloading OpenCV"
  mkdir -p "$RUNTIME_DIR/lib"
  curl -fsSL -o "$OPENCV_JAR" https://repo1.maven.org/maven2/org/openpnp/opencv/4.9.0-0/opencv-4.9.0-0.jar
  echo "$OPENCV_SHA256  $OPENCV_JAR" | sha256sum -c --status || { echo "OpenCV checksum mismatch"; exit 1; }
fi
bash "$APP_DIR/fetch-models.sh" "$RUNTIME_DIR/models"

# 5. The app. Media links are signed with a secret that survives restarts.
if [ ! -s "$DATA_DIR/media-secret" ]; then
  head -c 32 /dev/urandom | base64 > "$DATA_DIR/media-secret"
  chmod 600 "$DATA_DIR/media-secret"
fi

export SPOTME_DB_URL="jdbc:postgresql://localhost:5432/spotme"
export SPOTME_DB_USER=spotme
export SPOTME_DB_PASSWORD="$DB_PASSWORD"
export SPOTME_MEDIA_SECRET="$(cat "$DATA_DIR/media-secret")"
export SPOTME_STORAGE_DIR="$DATA_DIR/storage"
export SPOTME_MODELS_DIR="$RUNTIME_DIR/models"
export SPOTME_WEB_DIR="$APP_DIR/web"
export SPOTME_WEB_ORIGIN="${SPOTME_WEB_ORIGIN:-https://spotme.sites.tab.bot}"
export SPOTME_DEMO_ENABLED=true
export SPOTME_DEMO_PHOTOSDIR="$APP_DIR/demo-photos"
# one face engine, one worker thread and one decode at a time: memory over speed
export SPOTME_FACE_ENGINES=1
export SPOTME_FACE_OPENCVTHREADS=2
export SPOTME_WORKER_THREADS=1
export SPOTME_UPLOAD_MAXCONCURRENTDECODES=1
export SPOTME_UPLOAD_MAXPIXELS=16000000
export SPOTME_UPLOAD_MAXPHOTOSPEREVENT=60
export SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE=5
export SERVER_TOMCAT_THREADS_MAX=16
# /actuator/health shows disk, database and memory details, so the box can be watched from outside
export MANAGEMENT_ENDPOINT_HEALTH_SHOWDETAILS=always
export PORT

log "starting SpotMe on port $PORT"
cd "$APP_DIR"
# Measured on a fresh database: the JVM commits about 230 MB and the loaded face models add
# about 170 MB of native memory, so the heap stays small and glibc keeps fewer malloc arenas.
export MALLOC_ARENA_MAX=2
exec "$JAVA" -Xmx128m -Xss512k -XX:MaxMetaspaceSize=100m -XX:ReservedCodeCacheSize=32m \
  -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Djava.io.tmpdir="$DATA_DIR/tmp" \
  -cp "app/classes:app/lib/*:$RUNTIME_DIR/lib/*" dev.darshan.spotme.SpotMeApplication
