#!/usr/bin/env bash
# Lint, typecheck and build the console inside a Node container.
# node_modules lives in a named volume so the host checkout is never touched.
set -euo pipefail
cd "$(dirname "$0")/.."
docker run --rm \
  -v "$(pwd)/frontend:/app" \
  -v npg-frontend-node-modules:/app/node_modules \
  -v npg-npm-cache:/root/.npm \
  -w /app node:20-alpine \
  sh -c "npm ci --no-audit --no-fund >/dev/null && npm run -s lint && npm run -s typecheck && npm run -s build >/dev/null && echo FRONTEND_OK"
