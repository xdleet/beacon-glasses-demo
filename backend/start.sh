#!/bin/bash
# Start the glasses-demo backend on port 8901, detached.
# Sources the host's own environment file (~/.config/muse-dev/env) into
# this process ONLY. The key value is never printed, logged, copied, or
# committed. Run from the deployed backend directory, or pass it as $1.
set -e
DIR="${1:-$HOME/beacon-glasses-demo-backend}"
cd "$DIR"
if [ -f "$HOME/.config/muse-dev/env" ]; then
  # shellcheck disable=SC1091
  set -a; . "$HOME/.config/muse-dev/env"; set +a
fi
if [ -z "${MODEL_API_KEY:-}" ]; then
  echo "MODEL_API_KEY not set after sourcing ~/.config/muse-dev/env; refusing to start" >&2
  exit 1
fi
VENV="$HOME/local-tools/venv/bin"
exec nohup "$VENV/python" -m uvicorn app:app --host 0.0.0.0 --port 8901 \
  > "$DIR/backend.log" 2>&1 &
echo "started (pid $!); log: $DIR/backend.log"
