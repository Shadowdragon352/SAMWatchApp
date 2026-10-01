#!/usr/bin/env bash
set -euo pipefail
: "${DELIRIUMWATCH_TOKEN:?Set DELIRIUMWATCH_TOKEN to a long random value first}"
export DELIRIUMWATCH_TZ="${DELIRIUMWATCH_TZ:-Australia/Melbourne}"
exec uvicorn app.main:app --host "${DELIRIUMWATCH_HOST:-0.0.0.0}" --port "${DELIRIUMWATCH_PORT:-8000}"
