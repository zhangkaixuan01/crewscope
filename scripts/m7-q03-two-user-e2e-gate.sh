#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPOSITORY_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
PROJECT_NAME="${CREWSCOPE_Q03_PROJECT_NAME:-crewscope-m7-q03}"
WEB_PORT="${CREWSCOPE_Q03_WEB_PORT:-18080}"
RUNTIME_ROOT="${CREWSCOPE_Q03_RUNTIME_ROOT:-$REPOSITORY_ROOT/var/release/m7-q03/team-beta}"

export CREWSCOPE_DEMO_PROJECT_NAME="$PROJECT_NAME"
export CREWSCOPE_DEMO_RUNTIME_ROOT="$RUNTIME_ROOT"
export CREWSCOPE_WEB_PORT="$WEB_PORT"
export CREWSCOPE_Q03_BASE_URL="http://127.0.0.1:$WEB_PORT"
export CREWSCOPE_Q03_API_CONTAINER="${PROJECT_NAME}-api-1"
export CREWSCOPE_Q03_REDIS_CONTAINER="${PROJECT_NAME}-redis-1"

cleanup() {
  trap - EXIT INT TERM
  "$REPOSITORY_ROOT/deploy/team-beta/demo.sh" reset >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

# Start from an empty database/Redis pair owned only by the Q03 Compose project.
"$REPOSITORY_ROOT/deploy/team-beta/demo.sh" reset
"$REPOSITORY_ROOT/deploy/team-beta/demo.sh" up

cd "$REPOSITORY_ROOT/crewscope-web"
pnpm exec playwright test --config playwright.m7-q03.config.ts
