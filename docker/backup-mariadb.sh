#!/usr/bin/env bash
# Logical backup of the canonical database.
#   docker/backup-mariadb.sh [container] [backup-dir]
# Credentials come from the environment (DB_USER / DB_PASSWORD / DB_NAME), never from this file.
set -euo pipefail
CONTAINER=${1:-npg-db}
BACKUP_DIR=${2:-./backups}
: "${DB_PASSWORD:?set DB_PASSWORD}"
DB_USER=${DB_USER:-npg}
DB_NAME=${DB_NAME:-npg}
mkdir -p "$BACKUP_DIR"
FILE="$BACKUP_DIR/npg_mariadb_$(date -u +%Y%m%dT%H%M%SZ).sql.gz"
docker exec -e MYSQL_PWD="$DB_PASSWORD" "$CONTAINER" \
  mariadb-dump --single-transaction --routines --triggers -u "$DB_USER" "$DB_NAME" | gzip > "$FILE"
echo "MariaDB backup saved to $FILE"
