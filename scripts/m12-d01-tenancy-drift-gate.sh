#!/usr/bin/env sh
set -eu

# M12-D01 tenancy drift gate: upgrade + restore drill over one four-service team-beta Compose
# stack, asserting that the seeded organization ownership footprint does not drift across
#   seed     — fresh stack on the CURRENT local images (pre-upgrade baseline), REST-seeded
#              account/team/project/work-item, settled snapshot A + pg_dump archive
#   upgrade  — rebuild images from this working tree (Maven inside the api image build),
#              recreate the stack on the SAME volumes, snapshot B == snapshot A
#   restore  — drop & recreate the database on the kept volume, restore the snapshot-A dump,
#              bring the stack back, snapshot C == snapshot A
#
# The snapshot is the full row distribution: every organization-scoped table grouped by
# organization_id, plus row counts for every global table (organization root included).
# Strict equality — no carve-outs: runtime_worker re-registration upserts by stable key and
# heartbeats only touch columns, not row counts, so machinery restarts must not move counts.
# Zero new migrations in D01 also means flyway_schema_history (when it lives in the schema)
# is part of the equality and doubles as an empirical zero-migration proof.
#
# No secrets: OPEN registration, generated drill account, no model keys.
#
# Phases subset via CREWSCOPE_M12D01_PHASES (comma-separated; default seed,upgrade,restore —
# upgrade/restore reuse snapshot A from $RUNTIME, so run seed first on a fresh pass).
# Set CREWSCOPE_M12D01_KEEP_STACKS=1 to leave the stack running on exit.
#
# Discipline: `upgrade` runs Maven inside the api image build — never run it concurrently
# with another Maven session in this repository. Never point this gate at the dev stack:
# everything it touches is scoped to project $PROJECT and runtime $RUNTIME.

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPOSITORY_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)

PROJECT="${CREWSCOPE_M12D01_PROJECT:-m12-d01}"
PORT="${CREWSCOPE_M12D01_PORT:-18094}"
RUNTIME="${CREWSCOPE_M12D01_RUNTIME:-$REPOSITORY_ROOT/var/release/m12-d01}"
PHASES="${CREWSCOPE_M12D01_PHASES:-seed,upgrade,restore}"
export CREWSCOPE_REGISTRATION_MODE=OPEN

# The team-beta Alpine base images publish amd64-only manifests; this Apple-Silicon host
# builds and runs gate stacks under emulation. The drill asserts behavioral equality of
# row distributions, not latency, so emulation does not distort it.
export DOCKER_DEFAULT_PLATFORM="${CREWSCOPE_M12D01_PLATFORM:-linux/amd64}"

QUICKSTART="$REPOSITORY_ROOT/deploy/team-beta/quickstart.sh"
SNAPSHOT_A="$RUNTIME/snapshot-a.txt"
SNAPSHOT_B="$RUNTIME/snapshot-b.txt"
SNAPSHOT_C="$RUNTIME/snapshot-c.txt"
BASELINE_DUMP="$RUNTIME/d01-baseline.dump"
SEED_COORDS="$RUNTIME/seed-coords.json"

# Prefix assignments below front an external script, so they do not leak past the call.
stack() {
  CREWSCOPE_QUICKSTART_PROJECT_NAME="$PROJECT" \
  CREWSCOPE_QUICKSTART_RUNTIME_ROOT="$RUNTIME" \
  CREWSCOPE_WEB_PORT="$PORT" \
    "$QUICKSTART" "$@"
}

psql_stack() {
  stack compose exec -T postgres psql -U crewscope -d crewscope -A -t "$@"
}

psql_scalar() {
  psql_stack -c "$*" | head -n 1
}

has_phase() {
  case ",$PHASES," in *",$1,"*) ;; *) return 1 ;; esac
}

port_busy() {
  nc -z 127.0.0.1 "$1" 2>/dev/null
}

if port_busy "$PORT"; then
  echo "gate: port $PORT is already occupied — free it or set CREWSCOPE_M12D01_PORT" >&2
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

wait_postgres() {
  attempt=0
  while [ "$attempt" -lt 60 ]; do
    if stack compose exec -T postgres pg_isready -U crewscope -d crewscope >/dev/null 2>&1; then
      return 0
    fi
    attempt=$((attempt + 1))
    sleep 1
  done
  echo "gate: postgres not ready after 60s" >&2
  return 1
}

# One settled distribution snapshot: org-scoped tables as (table|organization_id|rows),
# global tables as (table|rows). \gexec executes the generated per-table queries in
# table-name order, so output ordering is deterministic and files are directly comparable.
take_snapshot() {
  snapshot_file="$1"
  {
    echo "# org-scoped row distribution: table|organization_id|rows"
    psql_stack <<'SQL'
SELECT format(
    'SELECT %L, organization_id::text, count(*) FROM crewscope.%I'
      || ' GROUP BY organization_id ORDER BY organization_id',
    c.table_name, c.table_name)
FROM information_schema.columns c
JOIN information_schema.tables t
  ON t.table_schema = c.table_schema AND t.table_name = c.table_name
 AND t.table_type = 'BASE TABLE'
WHERE c.table_schema = 'crewscope' AND c.column_name = 'organization_id'
ORDER BY c.table_name
\gexec
SQL
    echo "# global rows: table|rows"
    psql_stack <<'SQL'
SELECT format('SELECT %L, count(*) FROM crewscope.%I', t.table_name, t.table_name)
FROM information_schema.tables t
WHERE t.table_schema = 'crewscope' AND t.table_type = 'BASE TABLE'
  AND NOT EXISTS (
    SELECT 1 FROM information_schema.columns c
    WHERE c.table_schema = 'crewscope' AND c.table_name = t.table_name
      AND c.column_name = 'organization_id')
ORDER BY t.table_name
\gexec
SQL
  } >"$snapshot_file"
}

# Settle before freezing: two identical reads three seconds apart prove background
# convergence (outbox drain, projections) is done, not merely quiet for an instant.
settled_snapshot() {
  settled_target="$1"
  settled_try=0
  while [ "$settled_try" -lt 10 ]; do
    take_snapshot "$settled_target.attempt1"
    sleep 3
    take_snapshot "$settled_target.attempt2"
    if diff -q "$settled_target.attempt1" "$settled_target.attempt2" >/dev/null; then
      mv "$settled_target.attempt2" "$settled_target"
      rm -f "$settled_target.attempt1"
      return 0
    fi
    settled_try=$((settled_try + 1))
  done
  echo "gate: $settled_target never settled after 10 double-reads" >&2
  return 1
}

assert_snapshot_equals() {
  assert_label="$1"
  assert_expected="$2"
  assert_actual="$3"
  if diff -u "$assert_expected" "$assert_actual"; then
    echo "gate: $assert_label matches the baseline distribution"
  else
    echo "gate: $assert_label DRIFTED from the baseline distribution" >&2
    exit 1
  fi
}

# Ownership anchors re-asserted in every phase: the drilled organization row exists exactly
# once, and the schema still carries exactly the organization-scoped tables the S01 inventory
# froze plus the nine M12 tables added on top (V68 role/setting ×2, V69 governance jobs ×5,
# V70 quota ×2) — a live tripwire against silent column/schema drift.
assert_ownership_anchors() {
  if [ ! -f "$SEED_COORDS" ]; then
    echo "gate: $SEED_COORDS missing — run the seed phase first" >&2
    exit 1
  fi
  drilled_org=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["organizationId"])' "$SEED_COORDS")
  org_rows=$(psql_scalar "SELECT count(*) FROM crewscope.organization")
  if [ "$org_rows" != "1" ]; then
    echo "gate: organization row count is '$org_rows', expected exactly 1 on the single-organization profile" >&2
    exit 1
  fi
  drilled_org_row=$(psql_scalar "SELECT count(*) FROM crewscope.organization WHERE id = '$drilled_org'")
  if [ "$drilled_org_row" != "1" ]; then
    echo "gate: drilled session organization has no row — ownership anchor broken" >&2
    exit 1
  fi
  scoped_tables=$(psql_scalar "SELECT count(*) FROM information_schema.columns c JOIN information_schema.tables t ON t.table_schema = c.table_schema AND t.table_name = c.table_name AND t.table_type = 'BASE TABLE' WHERE c.table_schema = 'crewscope' AND c.column_name = 'organization_id'")
  if [ "$scoped_tables" != "138" ]; then
    echo "gate: organization-scoped table count is '$scoped_tables', expected 138 (S01 inventory 129 + V68/V69/V70 ×9)" >&2
    exit 1
  fi
  echo "gate: ownership anchors hold (organization = 1 row, drilled org bound, scoped tables = 138)"
}

cleanup() {
  trap - EXIT INT TERM
  if [ "${CREWSCOPE_M12D01_KEEP_STACKS:-0}" = "1" ]; then return 0; fi
  stack reset >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

stack init

if has_phase seed; then
  stack reset
  # Archive the pre-upgrade image identity: the baseline is whatever :local was when this
  # gate ran (for the D01 record run: the 2026-10-12 pre-D01 build). Compose still runs
  # :local; the tag only pins the baseline for re-runs.
  docker image inspect --format '{{.Id}} {{.Created}}' crewscope-backend:local \
    | tee "$RUNTIME/baseline-image.txt"
  docker tag crewscope-backend:local crewscope-backend:m12d01-baseline

  stack up
  wait_api

  work_items_before=$(psql_scalar "SELECT count(*) FROM crewscope.work_item")
  echo "gate: seeding drill footprint over $work_items_before existing work items"

  node "$SCRIPT_DIR/m12-d01/seed-tenancy-data.mjs" "http://127.0.0.1:$PORT" d01drill \
    | tee "$SEED_COORDS"
  python3 -c 'import json,sys; json.load(open(sys.argv[1]))' "$SEED_COORDS"

  # The work-item command is a 202 receipt: wait until its row is visible before freezing.
  # Total-count comparison — pre-existing demo rows cannot mask the landing.
  attempt=0
  work_items_after=""
  while [ "$attempt" -lt 60 ]; do
    work_items_after=$(psql_scalar "SELECT count(*) FROM crewscope.work_item")
    if [ "$work_items_after" -gt "$work_items_before" ]; then break; fi
    attempt=$((attempt + 1))
    sleep 2
  done
  if [ -z "$work_items_after" ] || [ "$work_items_after" -le "$work_items_before" ]; then
    echo "gate: seeded work item never landed in crewscope.work_item" >&2
    exit 1
  fi

  settled_snapshot "$SNAPSHOT_A"
  stack compose exec -T postgres pg_dump -U crewscope -d crewscope >"$BASELINE_DUMP"
  assert_ownership_anchors
  echo "gate: snapshot A frozen at $SNAPSHOT_A; baseline dump at $BASELINE_DUMP"
fi

if has_phase upgrade; then
  if [ ! -f "$SNAPSHOT_A" ]; then
    echo "gate: $SNAPSHOT_A missing — run the seed phase first" >&2
    exit 1
  fi
  baseline_id=$(docker image inspect --format '{{.Id}}' crewscope-backend:local)
  stack build
  upgraded_id=$(docker image inspect --format '{{.Id}}' crewscope-backend:local)
  docker image inspect --format '{{.Id}} {{.Created}}' crewscope-backend:local \
    | tee "$RUNTIME/upgraded-image.txt"
  if [ "$baseline_id" = "$upgraded_id" ]; then
    echo "gate: image id did not change across build — the upgrade drill would be vacuous" >&2
    exit 1
  fi

  # Same volumes, new image: compose recreates the api/web containers and keeps postgres data.
  stack up
  wait_api
  settled_snapshot "$SNAPSHOT_B"
  assert_snapshot_equals "snapshot B (post-upgrade)" "$SNAPSHOT_A" "$SNAPSHOT_B"
  assert_ownership_anchors
fi

if has_phase restore; then
  if [ ! -f "$SNAPSHOT_A" ] || [ ! -f "$BASELINE_DUMP" ]; then
    echo "gate: $SNAPSHOT_A / $BASELINE_DUMP missing — run the seed phase first" >&2
    exit 1
  fi
  stack down
  stack compose up --detach postgres
  wait_postgres

  # Recreate the database from scratch on the kept volume, then restore the snapshot-A dump.
  stack compose exec -T postgres psql -U crewscope -d postgres \
    -c "DROP DATABASE crewscope WITH (FORCE)" \
    -c "CREATE DATABASE crewscope OWNER crewscope"
  stack compose exec -T postgres psql -U crewscope -d crewscope -v ON_ERROR_STOP=1 \
    <"$BASELINE_DUMP" >/dev/null
  stack down
  stack up
  wait_api
  settled_snapshot "$SNAPSHOT_C"
  assert_snapshot_equals "snapshot C (post-restore)" "$SNAPSHOT_A" "$SNAPSHOT_C"
  assert_ownership_anchors
fi

echo "gate: M12-D01 tenancy drift drill passed (phases: $PHASES)"
