#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPOSITORY_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
PROJECT_NAME="${CREWSCOPE_Q02_PROJECT_NAME:-crewscope-m9b-q02}"
WEB_PORT="${CREWSCOPE_Q02_WEB_PORT:-18084}"
RUNTIME_ROOT="${CREWSCOPE_Q02_RUNTIME_ROOT:-$REPOSITORY_ROOT/var/release/m9b-q02/team-beta}"

export CREWSCOPE_QUICKSTART_PROJECT_NAME="$PROJECT_NAME"
export CREWSCOPE_QUICKSTART_RUNTIME_ROOT="$RUNTIME_ROOT"
export CREWSCOPE_WEB_PORT="$WEB_PORT"
export CREWSCOPE_REGISTRATION_MODE=OPEN
export CREWSCOPE_REAL_BASE_URL="http://127.0.0.1:$WEB_PORT"
export CREWSCOPE_REAL_API_CONTAINER="${PROJECT_NAME}-api-1"
export CREWSCOPE_REAL_REDIS_CONTAINER="${PROJECT_NAME}-redis-1"

# Optional real-credential passthrough (S4/S5). The gate itself never reads, logs or persists
# these values; the specs consume them from the process environment and submit them only through
# Node-side fetch calls so they never land in traces, screenshots or the command line.
#   CREWSCOPE_Q02_DEEPSEEK_API_KEY  — real DeepSeek key for the first live reply
#   CREWSCOPE_Q02_GITHUB_TOKEN      — real GitHub PAT for the import → draft-PR chain
#   CREWSCOPE_Q02_GITHUB_REPO       — "owner/name" writable test repository
# Without them the gated specs skip with a reason and the document records 需授权或环境.

cleanup() {
  trap - EXIT INT TERM
  "$REPOSITORY_ROOT/deploy/team-beta/quickstart.sh" reset >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

# Start from an empty database/Redis pair owned only by the M9b-Q02 Compose project; the specs
# must not inherit any pre-seeded account, binding or repository the gate claims to verify.
"$REPOSITORY_ROOT/deploy/team-beta/quickstart.sh" reset
"$REPOSITORY_ROOT/deploy/team-beta/quickstart.sh" build
"$REPOSITORY_ROOT/deploy/team-beta/quickstart.sh" up

cd "$REPOSITORY_ROOT/crewscope-web"
pnpm exec playwright test --config playwright.m9b-q02.config.ts
