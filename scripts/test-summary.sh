#!/usr/bin/env bash
# Summarise the latest surefire run: totals plus any failing test classes.
set -euo pipefail
cd "$(dirname "$0")/../backend/target/surefire-reports"
grep -h "Tests run" *.txt | awk -F'[ ,]+' '{t+=$3;f+=$5;e+=$7;s+=$9} END {printf "tests=%d failures=%d errors=%d skipped=%d\n",t,f,e,s}'
grep -l -E "FAILURE|ERROR" *.txt 2>/dev/null | sed 's/\.txt$//' | sed 's/^/FAILED: /' || true
