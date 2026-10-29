#!/usr/bin/env bash
set -euo pipefail
CONTAINER=${1:-npg-mongo}
BACKUP_DIR=${2:-./backups}
mkdir -p "$BACKUP_DIR"
DATE=$(date +%Y%m%d_%H%M%S)
docker exec "$CONTAINER" mongodump --db npg --out /tmp/npg_backup_$DATE
docker cp "$CONTAINER:/tmp/npg_backup_$DATE" "$BACKUP_DIR/npg_mongodb_$DATE"
docker exec "$CONTAINER" rm -rf /tmp/npg_backup_$DATE
echo "Backup saved to $BACKUP_DIR/npg_mongodb_$DATE"
