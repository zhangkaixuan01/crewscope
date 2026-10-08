#!/usr/bin/env sh
set -eu

# M10-Q02 real-model release gate: the closed-loop acceptance (M10 plan :157) over one
# augmented stack and one plain stack.
#
#   V-vector (18090) — the six M10 switches open (PGVECTOR + KNOWLEDGE_INDEX +
#              INDEX_WORKER + RETRIEVAL + INJECTION + SKILL; MEMORY stays closed per
#              written ruling B1). Hosts the whole arc: the closed-loop spec (knowledge +
#              repository ingestion → retrieval → injection → Review → Skill publish →
#              second execution loading the published Skill), the frozen comparison
#              protocol (12 tasks × off/on arms × 3 repetitions, S01 §4), the A01
#              retrieval smoke over the whole-repo crewscope-java index, the S01 JUnit
#              quality gate, and the full dual-image in-place upgrade quadrant with the
#              post-upgrade recheck.
#   P-plain  (18091) — every M10 switch closed on plain PostgreSQL: the existing
#              all-off contract and cross-team isolation specs re-run as the
#              "plain PostgreSQL closed-state" half of the contract.
#
# Real credentials are involved ONLY through environment passthrough (m9b-q02 precedent).
# The gate itself never reads, logs or persists any key value; the specs consume them from
# the process environment and submit them only through Node-side fetch calls so they never
# land in traces, screenshots, URLs or the command line:
#   CREWSCOPE_Q02_DEEPSEEK_API_KEY   — real DeepSeek chat key (loop + comparison executor)
#   CREWSCOPE_Q02_DASHSCOPE_API_KEY  — real DashScope embedding key (index + retrieval)
#   S01B_DASHSCOPE_KEY_FILE          — path to the DashScope key file for the s6 JUnit
#                                      quality gate (the test reads the file itself)
# Without the two API keys the closed-loop spec skips with a reason and the document
# records 需授权或环境; the comparison driver refuses to start without the loop's
# coordinates file.
#
# Phases can be subset through CREWSCOPE_M10Q02_PHASES (comma-separated); each phase is
# restartable on its own and the comparison driver resumes from its append-only JSONL:
#   s0 build the shared local images once (both projects tag crewscope-*-local)
#   s1 vector stack up (six switches open) + plain stack up + place both bare mirrors
#      (java-spring-lab materialized at the frozen baseline, crewscope-java at HEAD)
#   s2 closed-loop spec on Desktop only → writes var/release/m10-q02/loop-coordinates.json
#   s3 arm-off: re-up with RETRIEVAL/INJECTION closed → comparison driver --arm off
#   s4 arm-on: re-up with all six open → comparison driver --arm on → report.mjs verdict
#   s5 wait for the whole-repo crewscope-java index → session bootstrap → A01 mjs gate
#      (EXPECT_READY=1; on index failure the authorized fallback records the lab corpus
#      and the whole-repo conclusion stays 待执行 — user ruling 2026-10-07)
#   s6 S01 JUnit quality gate re-run (frozen thresholds, S01B_DASHSCOPE_KEY_FILE)
#   s7 full dual-image in-place upgrade: rows before → vector build → vector up with data
#      and switches open → rows not regressed → upgrade-recheck spec → A01 mjs again
#   s8 plain stack: all-off contract + cross-team isolation specs
#
# Set CREWSCOPE_M10Q02_KEEP_STACKS=1 to leave both stacks running on exit (post-mortem or
# resume); the default resets both. The vector stack is NEVER reset between s2 and s7 —
# the comparison arms and the upgrade quadrant live on the same un-wiped data.

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPOSITORY_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)

VECTOR_PROJECT="${CREWSCOPE_M10Q02_VECTOR_PROJECT:-crewscope-m10-q02-vector}"
VECTOR_PORT="${CREWSCOPE_M10Q02_VECTOR_PORT:-18090}"
VECTOR_RUNTIME="${CREWSCOPE_M10Q02_VECTOR_RUNTIME:-$REPOSITORY_ROOT/var/release/m10-q02/vector}"

PLAIN_PROJECT="${CREWSCOPE_M10Q02_PLAIN_PROJECT:-crewscope-m10-q02-plain}"
PLAIN_PORT="${CREWSCOPE_M10Q02_PLAIN_PORT:-18091}"
PLAIN_RUNTIME="${CREWSCOPE_M10Q02_PLAIN_RUNTIME:-$REPOSITORY_ROOT/var/release/m10-q02/plain}"

COORDINATES_FILE="$REPOSITORY_ROOT/var/release/m10-q02/loop-coordinates.json"
MIRROR_SRC="$REPOSITORY_ROOT/var/release/m10-q02/mirror-src"
PHASES="${CREWSCOPE_M10Q02_PHASES:-s0,s1,s2,s3,s4,s5,s6,s7,s8}"
export CREWSCOPE_REGISTRATION_MODE=OPEN

QUICKSTART="$REPOSITORY_ROOT/deploy/team-beta/quickstart.sh"

vector() {
  CREWSCOPE_QUICKSTART_PROJECT_NAME="$VECTOR_PROJECT" \
  CREWSCOPE_QUICKSTART_RUNTIME_ROOT="$VECTOR_RUNTIME" \
  CREWSCOPE_WEB_PORT="$VECTOR_PORT" \
    "$QUICKSTART" "$@"
}

plain() {
  CREWSCOPE_QUICKSTART_PROJECT_NAME="$PLAIN_PROJECT" \
  CREWSCOPE_QUICKSTART_RUNTIME_ROOT="$PLAIN_RUNTIME" \
  CREWSCOPE_WEB_PORT="$PLAIN_PORT" \
    "$QUICKSTART" "$@"
}

has_phase() {
  case ",$PHASES," in *",$1,"*) ;; *) return 1 ;; esac
}

# The augmented shape: all six switches except the two the comparison arms toggle.
# MEMORY stays closed everywhere (B1); PGVECTOR/INDEX/WORKER/SKILL stay open in both arms.
vector_up_augmented() {
  retrieval="${1:-true}"
  injection="${2:-true}"
  CREWSCOPE_PGVECTOR_ENABLED=true \
  CREWSCOPE_KNOWLEDGE_INDEX_ENABLED=true \
  CREWSCOPE_KNOWLEDGE_INDEX_WORKER_ENABLED=true \
  CREWSCOPE_KNOWLEDGE_RETRIEVAL_ENABLED="$retrieval" \
  CREWSCOPE_KNOWLEDGE_INJECTION_ENABLED="$injection" \
  CREWSCOPE_SKILL_ENABLED=true \
  CREWSCOPE_MEMORY_ENABLED=false \
    vector up
}

# The vector migration chain's own Flyway history — the upgrade quadrant's stability anchor
# (m10-q01 gate precedent).
vector_history_rows() {
  vector compose exec -T postgres psql -U crewscope -d crewscope -tAc \
    "SELECT count(*) FROM crewscope.flyway_vector_history"
}

# run_specs_q02 <base-url> <spec paths...> — Desktop project only: the Narrow twin would
# re-drive the same real-model flows and double the provider cost for no extra evidence.
# The spec filters must precede --project: playwright's --project is a greedy array option
# and swallows any positional argument that follows it.
run_specs_q02() {
  base_url="$1"
  shift
  cd "$REPOSITORY_ROOT/crewscope-web"
  CREWSCOPE_REAL_BASE_URL="$base_url" \
    pnpm exec playwright test --config playwright.m10-real.config.ts \
      "$@" --project='M10 Real Desktop'
  cd "$REPOSITORY_ROOT"
}

# Places one bare mirror beneath the vector runtime's managed repositories root. The clone
# runs inside the backend image with the resulting directory chowned to the worker uid —
# the managed-root resolver rejects mirrors owned by anyone else.
place_bare_mirror() {
  mirror_key="$1"
  mirror_src="$2"
  docker run --rm --user 0 --entrypoint /bin/sh \
    --mount "type=bind,src=$mirror_src,dst=/src,readonly" \
    --mount "type=bind,src=$VECTOR_RUNTIME/execution,dst=/execution" \
    crewscope-backend:local -ec '
      set -eu
      key="$0"
      rm -rf "/execution/repositories/$key.git"
      git clone --bare --quiet /src "/execution/repositories/$key.git"
      chown -R 10001:10001 "/execution/repositories/$key.git"
    ' "$mirror_key"
}

# Polls the whole-repo crewscope-java index job to a terminal state through the public API.
# Job terminal states are READY/FAILED/CANCELLED (KnowledgeIndexJobStatus — there is no
# SUCCEEDED). Exit 0 = READY; 3 = FAILED/CANCELLED (failureCode on stderr); 4 = budget hit.
wait_crew_index() {
  node --input-type=module -e '
    const { ApiClient } = await import("./scripts/m10-q02/lib/api.mjs")
    const { readFileSync } = await import("node:fs")
    const coordinates = JSON.parse(readFileSync("var/release/m10-q02/loop-coordinates.json", "utf8"))
    const client = new ApiClient(coordinates.baseUrl, coordinates.memberA)
    await client.ensureSession()
    const crew = coordinates.crewscopeJavaBinding
    const teamRoot = `/api/v1/organizations/${coordinates.orgId}/teams/${coordinates.teamId}`
    const deadline = Date.now() + 40 * 60 * 1000
    for (;;) {
      const jobs = await client.getJson(`${teamRoot}/knowledge/index/jobs?source=REPOSITORY&limit=100`)
      const mine = (jobs.items ?? []).filter(job => job.indexKey?.bindingId === crew.bindingId)
      const terminal = mine.find(job => ["READY", "FAILED", "CANCELLED"].includes(job.status))
      if (terminal) {
        console.error(`crewscope-java index job ${terminal.status}${terminal.failureCode ? ` (${terminal.failureCode})` : ""}`)
        process.exit(terminal.status === "READY" ? 0 : 3)
      }
      if (Date.now() > deadline) {
        console.error("crewscope-java index did not reach a terminal state within the wait budget")
        process.exit(4)
      }
      await new Promise(resolve => setTimeout(resolve, 30000))
    }
  '
}

# Prints the three RETRIEVAL_* environment lines for the A01 mjs gate, resolved from the
# coordinates file: `crew` targets the whole-repo index, `lab` the frozen fixture index.
retrieval_coords() {
  node -e '
    const c = JSON.parse(require("node:fs").readFileSync(process.argv[1], "utf8"))
    const target = process.argv[2] === "crew"
      ? { bindingId: c.crewscopeJavaBinding.bindingId, commit: c.crewscopeJavaBinding.commit }
      : { bindingId: c.repositoryBinding.bindingId, commit: c.repositoryBinding.baselineCommit }
    console.log(`RETRIEVAL_PROJECT_ID=${c.projectId}`)
    console.log(`RETRIEVAL_BINDING_ID=${target.bindingId}`)
    console.log(`RETRIEVAL_COMMIT=${target.commit}`)
  ' "$COORDINATES_FILE" "$1"
}

# Session bootstrap (member-level cookie + CSRF, never echoed) + A01 mjs with the repo
# target: whole-repo when its index reached READY, otherwise the authorized lab fallback with
# the whole-repo conclusion recorded as 待执行 (user ruling 2026-10-07).
run_a01_gate() {
  crew_wait=0
  wait_crew_index || crew_wait=$?
  if [ "$crew_wait" -eq 0 ]; then
    a01_target=crew
  else
    echo "gate: crewscope-java whole-repo index unavailable (wait exit $crew_wait) — A01 falls back to the lab corpus; whole-repo conclusion stays pending" >&2
    a01_target=lab
  fi
  # set -a exports the bootstrapped session material and retrieval coordinates to the
  # child node process without echoing them.
  set -a
  eval "$(node scripts/m10-q02/session-bootstrap.mjs)"
  eval "$(retrieval_coords "$a01_target")"
  set +a
  EXPECT_READY=1 node scripts/m10-a01/retrieval-quality-gate.mjs
}

cleanup() {
  trap - EXIT INT TERM
  if [ "${CREWSCOPE_M10Q02_KEEP_STACKS:-0}" = "1" ]; then return 0; fi
  plain reset >/dev/null 2>&1 || true
  vector reset >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

# Idempotent initialization; each phase below drives its own stack state.
vector init
plain init

if has_phase s0; then
  # Both projects consume the same fixed tags, so one build serves the whole gate.
  plain build
fi

if has_phase s1; then
  vector reset
  vector_up_augmented true true
  plain reset
  plain up

  # Deterministic lab fixture at the frozen baseline (evaluate.mjs materialize), and the
  # live repository itself as the whole-repo corpus, both as worker-owned bare mirrors.
  # materialize refuses a non-empty output directory, so clear the previous smoke's tree
  # first — s1 must stay re-runnable.
  mkdir -p "$MIRROR_SRC"
  rm -rf "$MIRROR_SRC/java-spring-lab"
  node "$REPOSITORY_ROOT/evaluation/m4/coding-v1/scripts/evaluate.mjs" materialize \
    --output "$MIRROR_SRC/java-spring-lab"
  place_bare_mirror java-spring-lab "$MIRROR_SRC/java-spring-lab"
  place_bare_mirror crewscope-java "$REPOSITORY_ROOT"
fi

if has_phase s2; then
  run_specs_q02 "http://127.0.0.1:$VECTOR_PORT" \
    e2e/m10-real/q02-knowledge-loop-real-api.spec.ts
  [ -f "$COORDINATES_FILE" ] || {
    echo "gate: the loop spec did not write $COORDINATES_FILE" >&2
    exit 1
  }
fi

if has_phase s3; then
  # Arm-off: same stack, same data, only RETRIEVAL + INJECTION closed (deployment-level
  # toggles — never a reset between s2 and s7).
  vector_up_augmented false false
  node scripts/m10-q02/run-comparison.mjs --arm off
fi

if has_phase s4; then
  vector_up_augmented true true
  node scripts/m10-q02/run-comparison.mjs --arm on
  node scripts/m10-q02/report.mjs
fi

if has_phase s5; then
  run_a01_gate
fi

if has_phase s6; then
  [ -n "${S01B_DASHSCOPE_KEY_FILE:-}" ] || {
    echo "gate: S01B_DASHSCOPE_KEY_FILE must point to the DashScope key file (path only, never the key itself)" >&2
    exit 1
  }
  [ -f "${S01B_DASHSCOPE_KEY_FILE:-}" ] || {
    echo "gate: S01B_DASHSCOPE_KEY_FILE is not a readable file" >&2
    exit 1
  }
  # Single Maven session discipline; frozen S01 thresholds never regress. The -am
  # reactor includes domain/application, which have no test by this name — surefire
  # turns that into a failure unless failIfNoSpecifiedTests is cleared.
  S01B_DASHSCOPE_KEY_FILE="$S01B_DASHSCOPE_KEY_FILE" \
    ./mvnw test -pl crewscope-infrastructure -am -Dtest=KnowledgeRetrievalQualityGateTest \
      -Dsurefire.failIfNoSpecifiedTests=false
fi

if has_phase s7; then
  rows_before=$(vector_history_rows)
  [ "$rows_before" -gt 0 ] || {
    echo "gate: flyway_vector_history is empty before the upgrade quadrant" >&2
    exit 1
  }

  # Full dual-image in-place upgrade with data in place and every switch open (the light
  # quadrant in the m10-q01 gate ran it all-closed; the full arc stays here by contract).
  vector build
  vector_up_augmented true true
  rows_after=$(vector_history_rows)
  [ "$rows_after" -ge "$rows_before" ] || {
    echo "gate: flyway_vector_history regressed across the upgrade ($rows_before -> $rows_after)" >&2
    exit 1
  }

  run_specs_q02 "http://127.0.0.1:$VECTOR_PORT" \
    e2e/m10-real/q02-upgrade-recheck-real-api.spec.ts
  run_a01_gate
fi

if has_phase s8; then
  run_specs_q02 "http://127.0.0.1:$PLAIN_PORT" \
    e2e/m10-real/feature-flags-off-real-api.spec.ts \
    e2e/m10-real/cross-team-isolation-real-api.spec.ts
fi
