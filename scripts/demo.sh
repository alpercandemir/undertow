#!/usr/bin/env bash
set -euo pipefail
# Run from the repository root. Existing artifacts are preserved; choose a new directory each time.
UNDERTOW_DEMO_DIR="${1:-.undertow/demo-$(date +%s)}"
python3 scripts/prepare-demo.py --output "$UNDERTOW_DEMO_DIR"
java -jar target/undertow.jar review --repo "$UNDERTOW_DEMO_DIR" --base HEAD~1 --head HEAD --replay "$UNDERTOW_DEMO_DIR/replay.json" --output "$UNDERTOW_DEMO_DIR/report"
cat "$UNDERTOW_DEMO_DIR/report/report.md"
