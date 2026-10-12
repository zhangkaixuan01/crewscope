#!/usr/bin/env sh
set -eu

# M11-Q01 real-stack gate: the cross-package hardening acceptance (M11 plan L127) over one
# collaboration stack on the four-service team-beta Compose shape.
#
#   Stack (18092) — CREWSCOPE_COLLABORATION_REALTIME_ENABLED=true with a 1s heartbeat
#              (the revocation full-chain drill needs the compressed sweep cycle). Hosts
#              the three attack specs:
#                cross-team-subscription-attack.spec.ts   — attacker account vs a foreign
#                                                            Team's WS scope + REST reads
#                revocation-full-chain.spec.ts            — REST suspend -> outbox poll ->
#                                                            member 4403 + presence teardown
#                redis-restart-resilience.spec.ts         — docker restart of the stack's
#                                                            redis while pages stay connected
#
# No secrets are involved: accounts are created through public OPEN registration.
#
# Phases can be subset through CREWSCOPE_M11Q01_PHASES (comma-separated):
#   build      rebuild the shared local images (Maven inside the api image build)
#   up         stack up with the channel open and the 1s heartbeat
#   specs      the three attack specs against the stack
#   redisdrill the redis-restart resilience drill (docker restart, no rebuild)
#
# Set CREWSCOPE_M11Q01_KEEP_STACKS=1 to leave the stack running on exit.
#
# Discipline: `build` runs Maven inside the api image build — never run it concurrently
# with another Maven session in this repository.

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPOSITORY_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)

PROJECT="${CREWSCOPE_M11Q01_PROJECT:-m11-q01}"
PORT="${CREWSCOPE_M11Q01_PORT:-18092}"
RUNTIME="${CREWSCOPE_M11Q01_RUNTIME:-$REPOSITORY_ROOT/var/release/m11-q01}"
PHASES="${CREWSCOPE_M11Q01_PHASES:-build,up,specs,redisdrill}"
export CREWSCOPE_REGISTRATION_MODE=OPEN

# The team-beta Alpine base images publish amd64-only manifests (apk-based runtime), so
# this Apple-Silicon host builds and runs the gate stacks under emulation — Compose and
# Buildx both respect DOCKER_DEFAULT_PLATFORM. The attack assertions here are behavioral
# (frames, codes, roster state), not latency numbers, so emulation does not distort them;
# the numeric budgets stay frozen on their S01/I01c evidence rows.
export DOCKER_DEFAULT_PLATFORM="${CREWSCOPE_M11Q01_PLATFORM:-linux/amd64}"

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

port_busy() {
  nc -z 127.0.0.1 "$1" 2>/dev/null
}

if port_busy "$PORT"; then
  echo "gate: port $PORT is already occupied — free it or set CREWSCOPE_M11Q01_PORT" >&2
  exit 1
fi

# The spec filter must precede --project: playwright's --project is a greedy array
# option and swallows any positional argument that follows it. Desktop only — the
# Narrow twin would re-drive the same real stack for no extra evidence.
run_specs() {
  base_url="$1"
  shift
  cd "$REPOSITORY_ROOT/crewscope-web"
  CREWSCOPE_REAL_BASE_URL="$base_url" \
    pnpm exec playwright test --config playwright.m11-real.config.ts \
    "$@" --project='M11 Real Desktop'
  cd "$REPOSITORY_ROOT"
}

cleanup() {
  trap - EXIT INT TERM
  if [ "${CREWSCOPE_M11Q01_KEEP_STACKS:-0}" = "1" ]; then return 0; fi
  stack reset >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

stack init

if has_phase build; then
  stack build
fi

if has_phase up; then
  stack reset
  CREWSCOPE_COLLABORATION_REALTIME_ENABLED=true \
  CREWSCOPE_COLLABORATION_REALTIME_HEARTBEAT_INTERVAL=1s \
    stack up
fi

if has_phase specs; then
  run_specs "http://127.0.0.1:$PORT" \
    e2e/m11-real/cross-team-subscription-attack.spec.ts \
    e2e/m11-real/revocation-full-chain.spec.ts
fi

if has_phase redisdrill; then
  run_specs "http://127.0.0.1:$PORT" e2e/m11-real/redis-restart-resilience.spec.ts
fi
