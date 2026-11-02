#!/bin/bash
# Stop the glasses-demo backend. The bracket trick keeps pkill from
# matching this script's own command line.
pkill -f "[u]vicorn app:app.*8901" || true
sleep 1
curl -s -m 3 http://127.0.0.1:8901/health || echo "backend stopped (health check no longer answers)"
