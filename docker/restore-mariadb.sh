#!/usr/bin/env bash
# Restores a backup made by backup-mariadb.sh. Stop the backend roles first.
#   docker/restore-mariadb.sh <backup.sql.gz> [container]
set -euo pipefail
FILE=${1:?usage: restore-mariadb.sh <backup.sql.gz> [container]}
CONTAINER=${2:-npg-db}
: "${DB_PASSWORD:?set DB_PASSWORD}"
DB_USER=${DB_USER:-npg}
DB_NAME=${DB_NAME:-npg}
gunzip -c "$FILE" | docker exec -i -e MYSQL_PWD="$DB_PASSWORD" "$CONTAINER" mariadb -u "$DB_USER" "$DB_NAME"
echo "Restored $FILE into $DB_NAME"
