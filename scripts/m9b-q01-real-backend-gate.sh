#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPOSITORY_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
PROJECT_NAME="${CREWSCOPE_Q01_PROJECT_NAME:-crewscope-m9b-q01}"
WEB_PORT="${CREWSCOPE_Q01_WEB_PORT:-18082}"
RUNTIME_ROOT="${CREWSCOPE_Q01_RUNTIME_ROOT:-$REPOSITORY_ROOT/var/release/m9b-q01/team-beta}"

export CREWSCOPE_QUICKSTART_PROJECT_NAME="$PROJECT_NAME"
export CREWSCOPE_QUICKSTART_RUNTIME_ROOT="$RUNTIME_ROOT"
export CREWSCOPE_WEB_PORT="$WEB_PORT"
export CREWSCOPE_REGISTRATION_MODE=OPEN
export CREWSCOPE_REAL_BASE_URL="http://127.0.0.1:$WEB_PORT"
export CREWSCOPE_REAL_API_CONTAINER="${PROJECT_NAME}-api-1"
export CREWSCOPE_REAL_REDIS_CONTAINER="${PROJECT_NAME}-redis-1"

cleanup() {
  trap - EXIT INT TERM
  "$REPOSITORY_ROOT/deploy/team-beta/quickstart.sh" reset >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

# Start from an empty database/Redis pair owned only by the M9b-Q01 Compose project; the specs
# must not inherit any pre-seeded binding or account the gate claims to verify.
"$REPOSITORY_ROOT/deploy/team-beta/quickstart.sh" reset
"$REPOSITORY_ROOT/deploy/team-beta/quickstart.sh" build
"$REPOSITORY_ROOT/deploy/team-beta/quickstart.sh" up

cd "$REPOSITORY_ROOT/crewscope-web"
pnpm exec playwright test --config playwright.m9b-q01.config.ts
