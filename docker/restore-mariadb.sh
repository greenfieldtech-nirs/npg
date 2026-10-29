#!/usr/bin/env bash
set -euo pipefail
CONTAINER=${1:-npg-db}
BACKUP_FILE=${2:?"Usage: restore-mariadb.sh [container] <backup-file.sql>"}
docker exec -i "$CONTAINER" mariadb -u npg -pnpg npg < "$BACKUP_FILE"
echo "Restored $BACKUP_FILE into $CONTAINER"
