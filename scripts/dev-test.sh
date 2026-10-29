#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

# Start the database and cache services in detached mode.
docker compose -f docker/docker-compose.dev.yml up -d db mongo redis

# Wait for db, mongo, and redis to report a healthy status.
echo "Waiting for database and cache services to become healthy..."
for service in db mongo redis; do
  until [ "$(docker compose -f docker/docker-compose.dev.yml ps -q "$service" | xargs docker inspect -f '{{.State.Health.Status}}' 2>/dev/null || true)" == "healthy" ]; do
    sleep 1
  done
done
echo "Database and cache services are healthy."

# Tests get their own databases: a running dev backend (APP_ROLE=all) polls the npg database's
# queues and would otherwise race the tests for their inbox, outbox and timer rows.
docker exec npg-db mariadb -uroot -proot -e \
  "CREATE DATABASE IF NOT EXISTS npg_test; GRANT ALL PRIVILEGES ON npg_test.* TO 'npg'@'%';"

# Run the Maven test suite inside a transient JDK container attached to the dev network.
# Maven is not included in the eclipse-temurin JRE/JDK Alpine images, so install it first.
docker run --rm \
  --network npg_dev \
  -v "$(pwd)/backend:/workspace" \
  -v "$(pwd)/docs:/docs:ro" \
  -v npg-m2-cache:/root/.m2 \
  -w /workspace \
  -e DB_HOST=npg-db \
  -e DB_PORT=3306 \
  -e DB_NAME=npg_test \
  -e DB_USER=npg \
  -e DB_PASSWORD=npg \
  -e MONGO_URI=mongodb://npg-mongo:27017 \
  -e MONGO_DB=npg_test \
  -e REDIS_URI=redis://npg-redis:6379 \
  eclipse-temurin:21-jdk-alpine \
  sh -c "apk add --no-cache maven >/dev/null && mvn -B -q test ${MVN_ARGS:-}"
