#!/usr/bin/env bash
set -euo pipefail
CONTAINER=${1:-npg-db}
BACKUP_DIR=${2:-./backups}
mkdir -p "$BACKUP_DIR"
DATE=$(date +%Y%m%d_%H%M%S)
docker exec "$CONTAINER" mariadb-dump -u npg -pnpg npg > "$BACKUP_DIR/npg_mariadb_$DATE.sql"
echo "Backup saved to $BACKUP_DIR/npg_mariadb_$DATE.sql"
