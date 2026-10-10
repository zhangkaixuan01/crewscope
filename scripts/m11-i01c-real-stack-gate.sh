#!/usr/bin/env sh
set -eu

# M11-I01c real-stack gate: the deployment and degradation acceptance (M11 plan §6 I01)
# over one collaboration stack and one degraded stack, both on the four-service
# team-beta Compose shape.
#
#   Main      (18087) — CREWSCOPE_COLLABORATION_REALTIME_ENABLED=true with a 1s
#              heartbeat (the 1013 slow-client drill needs the compressed cycle; the
#              shorter ping cadence is harmless for every other observation). Hosts the
#              real-browser WebSocket spec through Nginx's upgrade routing and the
#              product load drills: storm / steady / reconnect / leak / slow1013.
#   Degraded  (18088) — the channel switch left at its closed default: the WebSocket
#              endpoint answers 404 while the durable Team Activity SSE flow keeps
#              delivering authoritative updates. Hosts the degradation spec.
#
# No secrets are involved: accounts are created through public OPEN registration, and
# the load generator's disposable credentials live under var/ (git-ignored).
#
# Phases can be subset through CREWSCOPE_M11I01C_PHASES (comma-separated):
#   build  rebuild the shared local images (web must pick up nginx.conf changes)
#   up     main stack up with the channel open and the 1s heartbeat
#   specs  real-browser collaboration specs against the main stack
#   load   storm + steady + reconnect + leak drills (200-connection product baseline)
#   slow   slow1013 drill on the main stack (the I01a obligation loopback could not run)
#   degraded  degraded stack up + degradation specs, then reset
#
# Set CREWSCOPE_M11I01C_KEEP_STACKS=1 to leave both stacks running on exit.
#
# Discipline: `build` runs Maven inside the api image build — never run it concurrently
# with another Maven session in this repository.

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPOSITORY_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)

MAIN_PROJECT="${CREWSCOPE_M11I01C_MAIN_PROJECT:-m11-i01c}"
MAIN_PORT="${CREWSCOPE_M11I01C_MAIN_PORT:-18087}"
MAIN_RUNTIME="${CREWSCOPE_M11I01C_MAIN_RUNTIME:-$REPOSITORY_ROOT/var/release/m11-i01c/main}"

DEGRADED_PROJECT="${CREWSCOPE_M11I01C_DEGRADED_PROJECT:-m11-i01c-degraded}"
DEGRADED_PORT="${CREWSCOPE_M11I01C_DEGRADED_PORT:-18088}"
DEGRADED_RUNTIME="${CREWSCOPE_M11I01C_DEGRADED_RUNTIME:-$REPOSITORY_ROOT/var/release/m11-i01c/degraded}"

COUNT="${CREWSCOPE_M11I01C_COUNT:-200}"
STEADY_SECONDS="${CREWSCOPE_M11I01C_STEADY_SECONDS:-120}"
PHASES="${CREWSCOPE_M11I01C_PHASES:-build,up,specs,load,slow,degraded}"
export CREWSCOPE_REGISTRATION_MODE=OPEN

# The team-beta Alpine base images publish amd64-only manifests (apk-based runtime), so
# this Apple-Silicon host builds and runs the gate stacks under emulation — Compose and
# Buildx both respect DOCKER_DEFAULT_PLATFORM. Every number the load drills record is
# therefore internally comparable run-to-run on this machine, but it is an emulated
# single-host shape; the testing document states that limitation explicitly.
export DOCKER_DEFAULT_PLATFORM="${CREWSCOPE_M11I01C_PLATFORM:-linux/amd64}"

QUICKSTART="$REPOSITORY_ROOT/deploy/team-beta/quickstart.sh"
LOAD="$REPOSITORY_ROOT/scripts/m11-i01c/load-product-ws.mjs"

main() {
  CREWSCOPE_QUICKSTART_PROJECT_NAME="$MAIN_PROJECT" \
  CREWSCOPE_QUICKSTART_RUNTIME_ROOT="$MAIN_RUNTIME" \
  CREWSCOPE_WEB_PORT="$MAIN_PORT" \
    "$QUICKSTART" "$@"
}

degraded() {
  CREWSCOPE_QUICKSTART_PROJECT_NAME="$DEGRADED_PROJECT" \
  CREWSCOPE_QUICKSTART_RUNTIME_ROOT="$DEGRADED_RUNTIME" \
  CREWSCOPE_WEB_PORT="$DEGRADED_PORT" \
    "$QUICKSTART" "$@"
}

has_phase() {
  case ",$PHASES," in *",$1,"*) ;; *) return 1 ;; esac
}

port_busy() {
  nc -z 127.0.0.1 "$1" 2>/dev/null
}

for port in "$MAIN_PORT" "$DEGRADED_PORT"; do
  if port_busy "$port"; then
    echo "gate: port $port is already occupied — free it or set CREWSCOPE_M11I01C_*_PORT" >&2
    exit 1
  fi
done

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

run_load() {
  scenario="$1"
  shift
  node "$LOAD" --scenario "$scenario" --base-url "http://127.0.0.1:$MAIN_PORT" \
    --redis-container "$MAIN_PROJECT-redis-1" --api-container "$MAIN_PROJECT-api-1" \
    --count "$COUNT" "$@"
}

cleanup() {
  trap - EXIT INT TERM
  if [ "${CREWSCOPE_M11I01C_KEEP_STACKS:-0}" = "1" ]; then return 0; fi
  degraded reset >/dev/null 2>&1 || true
  main reset >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

main init
degraded init

if has_phase build; then
  main build
fi

if has_phase up; then
  main reset
  CREWSCOPE_COLLABORATION_REALTIME_ENABLED=true \
  CREWSCOPE_COLLABORATION_REALTIME_HEARTBEAT_INTERVAL=1s \
    main up
fi

if has_phase specs; then
  run_specs "http://127.0.0.1:$MAIN_PORT" e2e/m11-real/collaboration-ws.spec.ts
fi

if has_phase load; then
  run_load storm
  run_load steady --duration "$STEADY_SECONDS"
  run_load reconnect
  run_load leak
fi

if has_phase slow; then
  run_load slow1013
fi

if has_phase degraded; then
  degraded reset
  degraded up
  run_specs "http://127.0.0.1:$DEGRADED_PORT" e2e/m11-real/collaboration-degraded.spec.ts
  degraded reset
fi
