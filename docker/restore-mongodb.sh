#!/usr/bin/env bash
set -euo pipefail
CONTAINER=${1:-npg-mongo}
BACKUP_DIR_PATH=${2:?"Usage: restore-mongodb.sh [container] <backup-dir>"}
docker cp "$BACKUP_DIR_PATH" "$CONTAINER:/tmp/npg_restore"
docker exec "$CONTAINER" mongorestore --db npg /tmp/npg_restore/npg
docker exec "$CONTAINER" rm -rf /tmp/npg_restore
echo "Restored $BACKUP_DIR_PATH into $CONTAINER"
