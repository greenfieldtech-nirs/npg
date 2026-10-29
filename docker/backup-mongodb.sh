#!/usr/bin/env bash
# Archive of the raw-XML audit store.
#   docker/backup-mongodb.sh [container] [backup-dir]
# With authentication, set MONGO_USER / MONGO_PASSWORD in the environment.
set -euo pipefail
CONTAINER=${1:-npg-mongo}
BACKUP_DIR=${2:-./backups}
MONGO_DB=${MONGO_DB:-npg}
mkdir -p "$BACKUP_DIR"
FILE="$BACKUP_DIR/npg_mongodb_$(date -u +%Y%m%dT%H%M%SZ).archive.gz"
AUTH=()
if [[ -n "${MONGO_PASSWORD:-}" ]]; then
  AUTH=(--username "${MONGO_USER:-npg}" --password "$MONGO_PASSWORD" --authenticationDatabase admin)
fi
docker exec "$CONTAINER" mongodump --db "$MONGO_DB" --archive --gzip "${AUTH[@]}" > "$FILE"
echo "MongoDB backup saved to $FILE"
