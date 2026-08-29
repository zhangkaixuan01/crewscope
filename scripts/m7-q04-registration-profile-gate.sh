#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPOSITORY_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
PROJECT_NAME="${CREWSCOPE_Q04_PROJECT_NAME:-crewscope-m7-q04}"
WEB_PORT="${CREWSCOPE_Q04_WEB_PORT:-18081}"
RUNTIME_ROOT="${CREWSCOPE_Q04_RUNTIME_ROOT:-$REPOSITORY_ROOT/var/release/m7-q04/team-beta}"
DEMO_SCRIPT="$REPOSITORY_ROOT/deploy/team-beta/demo.sh"

export CREWSCOPE_DEMO_PROJECT_NAME="$PROJECT_NAME"
export CREWSCOPE_DEMO_RUNTIME_ROOT="$RUNTIME_ROOT"
export CREWSCOPE_WEB_PORT="$WEB_PORT"
export CREWSCOPE_REGISTRATION_MODE=OPEN
export CREWSCOPE_DEMO_BUILD="${CREWSCOPE_Q04_BUILD_IMAGES:-false}"
export CREWSCOPE_Q04_BASE_URL="http://127.0.0.1:$WEB_PORT"
export CREWSCOPE_Q04_REPOSITORY_ROOT="$REPOSITORY_ROOT"
export CREWSCOPE_Q04_DEMO_SCRIPT="$DEMO_SCRIPT"

cleanup() {
  trap - EXIT INT TERM
  "$DEMO_SCRIPT" reset >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

if [ "$CREWSCOPE_DEMO_BUILD" != true ]; then
  docker image inspect crewscope-backend:demo crewscope-web:demo >/dev/null 2>&1 || {
    echo "M7-Q04 requires crewscope-backend:demo and crewscope-web:demo when image builds are disabled." >&2
    echo "Set CREWSCOPE_Q04_BUILD_IMAGES=true to build them in this standalone gate." >&2
    exit 1
  }
fi

"$DEMO_SCRIPT" reset
"$DEMO_SCRIPT" up

cd "$REPOSITORY_ROOT/crewscope-web"
pnpm exec playwright test --config playwright.m7-q04.config.ts

echo "M7-Q04 registration Profile gate passed."
