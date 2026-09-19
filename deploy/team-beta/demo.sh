#!/usr/bin/env sh
set -eu

# The historical ten-service demo was intentionally removed. Team Beta now uses the same
# single-Compose workflow for local and server deployments.
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec "$SCRIPT_DIR/quickstart.sh" "$@"
