#!/usr/bin/env sh
set -eu

# Backward-compatible alias. The lightweight stack lives beside Team Beta and follows the same
# one-Compose-file workflow as New API.
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
export CREWSCOPE_QUICKSTART_PROJECT_NAME="${CREWSCOPE_LOCAL_DEMO_PROJECT_NAME:-${CREWSCOPE_QUICKSTART_PROJECT_NAME:-crewscope-team-beta}}"
export CREWSCOPE_QUICKSTART_RUNTIME_ROOT="${CREWSCOPE_LOCAL_DEMO_RUNTIME_ROOT:-${CREWSCOPE_QUICKSTART_RUNTIME_ROOT:-$SCRIPT_DIR/team-beta/.runtime}}"
exec "$SCRIPT_DIR/team-beta/quickstart.sh" "$@"
