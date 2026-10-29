#!/usr/bin/env bash
# Restores an archive made by backup-mongodb.sh (drops the collections it restores).
#   docker/restore-mongodb.sh <backup.archive.gz> [container]
set -euo pipefail
FILE=${1:?usage: restore-mongodb.sh <backup.archive.gz> [container]}
CONTAINER=${2:-npg-mongo}
AUTH=()
if [[ -n "${MONGO_PASSWORD:-}" ]]; then
  AUTH=(--username "${MONGO_USER:-npg}" --password "$MONGO_PASSWORD" --authenticationDatabase admin)
fi
docker exec -i "$CONTAINER" mongorestore --archive --gzip --drop "${AUTH[@]}" < "$FILE"
echo "Restored $FILE"
