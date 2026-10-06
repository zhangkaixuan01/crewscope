#!/usr/bin/env sh
set -eu

# M10-Q01 release gate: the hardening close-out over two throwaway Compose stacks.
#
#   P-plain  (18087) — every M10 switch closed. Runs the cross-team isolation probe (E1,
#              probe tier) and the all-off combination contract (E2).
#   P-vector (18088) — the augmented shape the five existing m10-real specs freeze on
#              (pgvector + index gate + skill gate open, worker off), then the Q01
#              combinations on top: combo-full (E3 — a job fails closed and visibly
#              without a model connection, the preview degrades but never errors),
#              combo-index-only (E3 — preview answers RETRIEVAL_DISABLED while the skill
#              catalog still publishes), and finally the light upgrade quadrant: every
#              switch closed on top of an installed pgvector (sticky image +
#              flyway_vector_history preserved, preview degraded), then build → up again
#              (M10 plan §10.7 row 7). The full dual-image in-place upgrade stays Q02.
#
# No real credentials are involved anywhere in this gate: the no-model-connection shapes
# are themselves objects of acceptance, so there is nothing to pass through or redact.
#
# Phases can be subset through CREWSCOPE_M10Q01_PHASES (comma-separated):
#   s0 build the shared local images once (both projects tag crewscope-*-local)
#   s1 plain stack up + E1 probe tier + E2 all-off contract
#   s2 vector stack up on the frozen augmented shape
#   s3 five frozen m10-real specs — the zero-regression denominator
#   s4 E1 on the vector stack (the skill collision layer is live)
#   s5 combo-full up + E3
#   s6 combo-index-only up + E3
#   s7 light upgrade quadrant (off → assert → build → up → assert)

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPOSITORY_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)

PLAIN_PROJECT="${CREWSCOPE_M10Q01_PLAIN_PROJECT:-crewscope-m10-q01-plain}"
PLAIN_PORT="${CREWSCOPE_M10Q01_PLAIN_PORT:-18087}"
PLAIN_RUNTIME="${CREWSCOPE_M10Q01_PLAIN_RUNTIME:-$REPOSITORY_ROOT/var/release/m10-q01/plain}"

VECTOR_PROJECT="${CREWSCOPE_M10Q01_VECTOR_PROJECT:-crewscope-m10-q01-vector}"
VECTOR_PORT="${CREWSCOPE_M10Q01_VECTOR_PORT:-18088}"
VECTOR_RUNTIME="${CREWSCOPE_M10Q01_VECTOR_RUNTIME:-$REPOSITORY_ROOT/var/release/m10-q01/vector}"

PHASES="${CREWSCOPE_M10Q01_PHASES:-s0,s1,s2,s3,s4,s5,s6,s7}"
export CREWSCOPE_REGISTRATION_MODE=OPEN

QUICKSTART="$REPOSITORY_ROOT/deploy/team-beta/quickstart.sh"

plain() {
  CREWSCOPE_QUICKSTART_PROJECT_NAME="$PLAIN_PROJECT" \
  CREWSCOPE_QUICKSTART_RUNTIME_ROOT="$PLAIN_RUNTIME" \
  CREWSCOPE_WEB_PORT="$PLAIN_PORT" \
    "$QUICKSTART" "$@"
}

vector() {
  CREWSCOPE_QUICKSTART_PROJECT_NAME="$VECTOR_PROJECT" \
  CREWSCOPE_QUICKSTART_RUNTIME_ROOT="$VECTOR_RUNTIME" \
  CREWSCOPE_WEB_PORT="$VECTOR_PORT" \
    "$QUICKSTART" "$@"
}

has_phase() {
  case ",$PHASES," in *",$1,"*) ;; *) return 1 ;; esac
}

# The vector migration chain's own Flyway history — the upgrade quadrant's stability anchor.
vector_history_rows() {
  vector compose exec -T postgres psql -U crewscope -d crewscope -tAc \
    "SELECT count(*) FROM crewscope.flyway_vector_history"
}

# run_specs <base-url> <stack-declaration-or-dash> <spec paths...>
run_specs() {
  base_url="$1"
  stack_decl="$2"
  shift 2
  cd "$REPOSITORY_ROOT/crewscope-web"
  if [ "$stack_decl" = "-" ]; then
    CREWSCOPE_REAL_BASE_URL="$base_url" \
      pnpm exec playwright test --config playwright.m10-real.config.ts "$@"
  else
    CREWSCOPE_REAL_BASE_URL="$base_url" CREWSCOPE_M10Q01_STACK="$stack_decl" \
      pnpm exec playwright test --config playwright.m10-real.config.ts "$@"
  fi
  cd "$REPOSITORY_ROOT"
}

cleanup() {
  trap - EXIT INT TERM
  plain reset >/dev/null 2>&1 || true
  vector reset >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

# Idempotent: the generated keys survive a second init, and each phase below resets its
# own stack anyway.
plain init
vector init

if has_phase s0; then
  # Both projects consume the same fixed tags, so one build serves the whole gate.
  plain build
fi

if has_phase s1; then
  plain reset
  plain up
  run_specs "http://127.0.0.1:$PLAIN_PORT" - \
    e2e/m10-real/cross-team-isolation-real-api.spec.ts \
    e2e/m10-real/feature-flags-off-real-api.spec.ts
fi

if has_phase s2; then
  vector reset
  CREWSCOPE_PGVECTOR_ENABLED=true \
  CREWSCOPE_KNOWLEDGE_INDEX_ENABLED=true \
  CREWSCOPE_KNOWLEDGE_INDEX_WORKER_ENABLED=false \
  CREWSCOPE_SKILL_ENABLED=true \
    vector up
fi

if has_phase s3; then
  run_specs "http://127.0.0.1:$VECTOR_PORT" - \
    e2e/m10-real/knowledge-real-api.spec.ts \
    e2e/m10-real/index-real-api.spec.ts \
    e2e/m10-real/memory-real-api.spec.ts \
    e2e/m10-real/skills-real-api.spec.ts \
    e2e/m10-real/agent-cost-real-api.spec.ts
fi

if has_phase s4; then
  run_specs "http://127.0.0.1:$VECTOR_PORT" - \
    e2e/m10-real/cross-team-isolation-real-api.spec.ts
fi

if has_phase s5; then
  CREWSCOPE_PGVECTOR_ENABLED=true \
  CREWSCOPE_KNOWLEDGE_INDEX_ENABLED=true \
  CREWSCOPE_KNOWLEDGE_INDEX_WORKER_ENABLED=true \
  CREWSCOPE_KNOWLEDGE_RETRIEVAL_ENABLED=true \
  CREWSCOPE_KNOWLEDGE_INJECTION_ENABLED=true \
  CREWSCOPE_SKILL_ENABLED=true \
  CREWSCOPE_MEMORY_ENABLED=true \
  CREWSCOPE_BUDGET_ALERT_ENABLED=true \
    vector up
  run_specs "http://127.0.0.1:$VECTOR_PORT" combo-full \
    e2e/m10-real/feature-combinations-real-api.spec.ts
fi

if has_phase s6; then
  CREWSCOPE_PGVECTOR_ENABLED=true \
  CREWSCOPE_KNOWLEDGE_INDEX_ENABLED=true \
  CREWSCOPE_KNOWLEDGE_INDEX_WORKER_ENABLED=true \
  CREWSCOPE_SKILL_ENABLED=true \
    vector up
  run_specs "http://127.0.0.1:$VECTOR_PORT" combo-index-only \
    e2e/m10-real/feature-combinations-real-api.spec.ts
fi

if has_phase s7; then
  rows_before=$(vector_history_rows)
  [ "$rows_before" -gt 0 ] || {
    echo "gate: flyway_vector_history is empty before the upgrade quadrant" >&2
    exit 1
  }

  # Every switch closed on top of the installed pgvector: the sticky marker keeps the
  # pgvector image layered and the vector history intact — §10.7 row 7's left half.
  vector up
  rows_off=$(vector_history_rows)
  [ "$rows_off" -eq "$rows_before" ] || {
    echo "gate: flyway_vector_history changed ($rows_before -> $rows_off) while all switches were off" >&2
    exit 1
  }
  run_specs "http://127.0.0.1:$VECTOR_PORT" - \
    e2e/m10-real/feature-flags-off-real-api.spec.ts

  # The runbook's upgrade half: build → up, still every switch closed.
  vector build
  vector up
  rows_upgraded=$(vector_history_rows)
  [ "$rows_upgraded" -eq "$rows_before" ] || {
    echo "gate: flyway_vector_history changed ($rows_before -> $rows_upgraded) across the upgrade" >&2
    exit 1
  }
  run_specs "http://127.0.0.1:$VECTOR_PORT" - \
    e2e/m10-real/feature-flags-off-real-api.spec.ts
fi
