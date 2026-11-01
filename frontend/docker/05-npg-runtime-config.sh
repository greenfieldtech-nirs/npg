#!/bin/sh
# Runs from the nginx image's /docker-entrypoint.d before the templates are rendered.
# GA_MEASUREMENT_ID ends up inside /runtime-config.js, so only a GA4 measurement id (or nothing,
# which turns analytics off) is accepted: anything else stops the container.
set -eu
case "${GA_MEASUREMENT_ID:-}" in
    "") echo "Google Analytics: off (GA_MEASUREMENT_ID not set)" ;;
    G-*[!A-Z0-9-]*) echo "GA_MEASUREMENT_ID must look like G-XXXXXXXXXX" >&2; exit 1 ;;
    G-?*) echo "Google Analytics: $GA_MEASUREMENT_ID (public site, after consent)" ;;
    *) echo "GA_MEASUREMENT_ID must look like G-XXXXXXXXXX" >&2; exit 1 ;;
esac
