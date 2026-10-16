#!/usr/bin/env sh
set -eu

# M12-Q02 release gate: the enterprise mainline (M12 plan §6/§8) — one four-service
# team-beta Compose stack, brought up through the plain quickstart default path (no OIDC,
# no quota, no realtime env keys: that default path itself is the §8-2 acceptance face),
# then driven end to end by an automated browser:
#
#   enterprise-mainline.spec.ts — bootstrap owner grants ORG_ADMIN/ORG_AUDITOR to OPEN
#                 accounts, organization settings lifecycle (F01), quota configuration and
#                 usage projection (F02), the SLI read plane, a governance export whose
#                 manifest pins live WorkGraph relations, the full two-step account
#                 deletion chain (unconfirmed process rejected, sessions revoked by the
#                 security-version bump), and single-session revocation (F03).
#
# The fresh stack's only ORG_OWNER is the bootstrap operator (V68 backfill targets legacy
# data; ensureInitialOwner hands the role to the operator at startup). The gate reads the
# stack-local bootstrap password quickstart generated — generated for this stack, destroyed
# with it, never echoed, never committed — and passes it to the spec through
# CREWSCOPE_M12Q02_OPERATOR_PASSWORD. All other accounts come from public OPEN registration
# and no model key is read.
#
# Phases can be subset through CREWSCOPE_M12Q02_PHASES (comma-separated):
#   build   rebuild the shared local images (Maven inside the api image build)
#   up      stack reset + stack up on the plain quickstart defaults (no extra env)
#   main    the enterprise mainline spec against the stack (the Q02 acceptance face)
#   specs   every m12-real spec against the stack (belt-and-braces full sweep)
#
# Set CREWSCOPE_M12Q02_KEEP_STACKS=1 to leave the stack running on exit.
#
# Discipline: `build` runs Maven inside the api image build — never run it concurrently
# with another Maven session in this repository.

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPOSITORY_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)

PROJECT="${CREWSCOPE_M12Q02_PROJECT:-m12-q02}"
PORT="${CREWSCOPE_M12Q02_PORT:-18095}"
RUNTIME="${CREWSCOPE_M12Q02_RUNTIME:-$REPOSITORY_ROOT/var/release/m12-q02}"
PHASES="${CREWSCOPE_M12Q02_PHASES:-build,up,main}"
export CREWSCOPE_REGISTRATION_MODE=OPEN

# The team-beta Alpine base images publish amd64-only manifests (apk-based runtime), so
# this Apple-Silicon host builds and runs the gate stacks under emulation — Compose and
# Buildx both respect DOCKER_DEFAULT_PLATFORM. The mainline assertions here are behavioral
# (status codes, role projections, manifest families), not latency numbers, so emulation
# does not distort them.
export DOCKER_DEFAULT_PLATFORM="${CREWSCOPE_M12Q02_PLATFORM:-linux/amd64}"

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
  echo "gate: port $PORT is already occupied — free it or set CREWSCOPE_M12Q02_PORT" >&2
  exit 1
fi

wait_api() {
  attempt=0
  while [ "$attempt" -lt 180 ]; do
    if curl -sf -o /dev/null "http://127.0.0.1:$PORT/api/v1/auth/session"; then return 0; fi
    attempt=$((attempt + 1))
    sleep 1
  done
  echo "gate: api not ready on port $PORT after 180s" >&2
  return 1
}

# The spec filter must precede --project: playwright's --project is a greedy array
# option and swallows any positional argument that follows it. Desktop only — the
# Narrow twin would re-drive the same real stack for no extra evidence.
run_specs() {
  base_url="$1"
  shift
  cd "$REPOSITORY_ROOT/crewscope-web"
  CREWSCOPE_REAL_BASE_URL="$base_url" \
    pnpm exec playwright test --config playwright.m12-real.config.ts \
    "$@" --project='M12 Real Desktop'
  cd "$REPOSITORY_ROOT"
}

cleanup() {
  trap - EXIT INT TERM
  if [ "${CREWSCOPE_M12Q02_KEEP_STACKS:-0}" = "1" ]; then return 0; fi
  stack reset >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

stack init

if has_phase build; then
  stack build
fi

if has_phase up; then
  stack reset
  # Zero extra env: the compose defaults (no OIDC/quota/realtime keys, security.mode=local)
  # are exactly what §8-2 pins — the default install path must carry the enterprise read
  # and governance planes without any deployment-side capability switch.
  stack up
fi

if has_phase main; then
  wait_api || exit 1
  if [ ! -f "$RUNTIME/bootstrap_password" ]; then
    echo "gate: $RUNTIME/bootstrap_password missing — the stack never came up through quickstart" >&2
    exit 1
  fi
  # Top-level assignment + export only: prefix assignments stay exported after a function
  # call on this macOS sh host (M11 harness note), and this generated, stack-local secret
  # must never reach the trace or the log output.
  CREWSCOPE_M12Q02_OPERATOR_PASSWORD="$(cat "$RUNTIME/bootstrap_password")"
  export CREWSCOPE_M12Q02_OPERATOR_PASSWORD
  run_specs "http://127.0.0.1:$PORT" e2e/m12-real/enterprise-mainline.spec.ts
fi

if has_phase specs; then
  wait_api || exit 1
  run_specs "http://127.0.0.1:$PORT" e2e/m12-real
fi
