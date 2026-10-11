#!/usr/bin/env sh
set -eu

# M11-F02 real-stack gate: the WorkGraph visualization acceptance over one standard
# four-service team-beta Compose stack (no channel switches — the graph tier needs no
# collaboration toggles; relation fan-out rides the same stack either way).
#
#   build  rebuild the shared local images (web must pick up the F02 frontend commit —
#          a stale web image would serve the old bundle and the graph view would 404)
#   up     stack up on the default channel settings
#   specs  the real-browser workgraph spec against the Nginx entry point
#
# Phases can be subset through CREWSCOPE_M11F02_PHASES (comma-separated). Set
# CREWSCOPE_M11F02_KEEP_STACK=1 to leave the stack running on exit.
#
# Discipline: `build` runs Maven inside the api image build — never run it concurrently
# with another Maven session in this repository.

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
REPOSITORY_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)

PROJECT="${CREWSCOPE_M11F02_PROJECT:-m11-f02}"
PORT="${CREWSCOPE_M11F02_PORT:-18089}"
RUNTIME="${CREWSCOPE_M11F02_RUNTIME:-$REPOSITORY_ROOT/var/release/m11-f02/main}"

PHASES="${CREWSCOPE_M11F02_PHASES:-build,up,specs}"
export CREWSCOPE_REGISTRATION_MODE=OPEN

# The team-beta Alpine base images publish amd64-only manifests (apk-based runtime), so
# this Apple-Silicon host builds and runs the gate stack under emulation — Compose and
# Buildx both respect DOCKER_DEFAULT_PLATFORM (same shape as the I01c gate).
export DOCKER_DEFAULT_PLATFORM="${CREWSCOPE_M11F02_PLATFORM:-linux/amd64}"

QUICKSTART="$REPOSITORY_ROOT/deploy/team-beta/quickstart.sh"

stack() {
  CREWSCOPE_QUICKSTART_PROJECT_NAME="$PROJECT" \
  CREWSCOPE_QUICKSTART_RUNTIME_ROOT="$RUNTIME" \
  CREWSCOPE_WEB_PORT="$PORT" \
    "$QUICKSTART" "$@"
}

has_phase() {
  case ",$PHASES," in *",$1,"*) ;; *) return 1 ;; esac
}

if nc -z 127.0.0.1 "$PORT" 2>/dev/null; then
  echo "gate: port $PORT is already occupied — free it or set CREWSCOPE_M11F02_PORT" >&2
  exit 1
fi

cleanup() {
  trap - EXIT INT TERM
  if [ "${CREWSCOPE_M11F02_KEEP_STACKS:-0}" = "1" ]; then return 0; fi
  stack reset >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

stack init

if has_phase build; then
  stack build
fi

if has_phase up; then
  stack reset
  stack up
fi

if has_phase specs; then
  # The spec filter must precede --project: playwright's --project is a greedy array
  # option and swallows any positional argument that follows it.
  cd "$REPOSITORY_ROOT/crewscope-web"
  CREWSCOPE_REAL_BASE_URL="http://127.0.0.1:$PORT" \
    pnpm exec playwright test --config playwright.m11-real.config.ts \
    e2e/m11-real/workgraph.spec.ts --project='M11 Real Desktop'
  cd "$REPOSITORY_ROOT"
fi
