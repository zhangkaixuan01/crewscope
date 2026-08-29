#!/usr/bin/env sh
set -eu

OPERATIONS_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# shellcheck disable=SC1091
. "$OPERATIONS_DIR/common.sh"

usage() {
  printf 'Usage: %s /absolute/path/team-beta.env /absolute/path/backup.bundle.enc [--enable-traffic]\n' "$0"
}

[ "$#" -ge 2 ] && [ "$#" -le 3 ] || { usage; exit 2; }
load_operator_environment "$1"
BUNDLE=$2
require_absolute_path "$BUNDLE" "backup bundle"
require_regular_file "$BUNDLE" "backup bundle"
ENABLE_TRAFFIC=false
if [ "$#" -eq 3 ]; then
  [ "$3" = '--enable-traffic' ] || fail "the only optional flag is --enable-traffic"
  ENABLE_TRAFFIC=true
fi

case "$BUNDLE" in
  *.bundle.enc) ENVELOPE=${BUNDLE%.bundle.enc}.envelope.json ;;
  *) fail "backup bundle must end in .bundle.enc" ;;
esac
require_regular_file "$ENVELOPE" "backup envelope"

for service in api worker web; do
  running_service "$service" && fail "$service must be stopped before restore"
done

mkdir -p "$CREWSCOPE_BACKUP_ROOT"
LOCK_DIR="$CREWSCOPE_BACKUP_ROOT/.restore.lock"
mkdir "$LOCK_DIR" 2>/dev/null || fail "another Team Beta restore is active"
STAGING=$(mktemp -d "$TEMP_ROOT/crewscope-team-beta-restore.XXXXXX")
PAYLOAD="$STAGING/payload"
mkdir "$PAYLOAD"
STARTED_SERVICES=''
START_EPOCH=$(date +%s)
STARTED_AT=$(date -u '+%Y-%m-%dT%H:%M:%SZ')

cleanup() {
  rmdir "$LOCK_DIR" 2>/dev/null || true
  safe_remove_temporary "$STAGING"
}
trap cleanup EXIT HUP INT TERM

node "$RECOVERY_TOOL" verify-ciphertext "$BUNDLE" "$ENVELOPE" >/dev/null
openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -md sha256 \
  -pass "file:$CREWSCOPE_BACKUP_PASSPHRASE_FILE" \
  -in "$BUNDLE" -out "$STAGING/payload.tar.gz" \
  || fail "backup decryption failed"
validate_archive_entries "$STAGING/payload.tar.gz"
tar -xzf "$STAGING/payload.tar.gz" -C "$PAYLOAD"
node "$RECOVERY_TOOL" verify-payload "$PAYLOAD" "$ENVELOPE" \
  "$CREWSCOPE_RESTORE_MIN_SCHEMA" "$CREWSCOPE_RESTORE_MAX_SCHEMA" \
  >"$STAGING/verified-manifest.json"
verify_required_key_ids "$STAGING/verified-manifest.json"

SOURCE_SCHEMA=$(jq -r '.schemaVersion' "$STAGING/verified-manifest.json")
BACKUP_ID=$(jq -r '.backupId' "$STAGING/verified-manifest.json")
CREATED_AT=$(jq -r '.createdAt' "$STAGING/verified-manifest.json")

ARTIFACT_ROOT="$CREWSCOPE_DATA_ROOT/artifacts"
mkdir -p "$ARTIFACT_ROOT"
[ ! -L "$ARTIFACT_ROOT" ] || fail "Artifact restore target must not be a symbolic link"
[ -z "$(find "$ARTIFACT_ROOT" -mindepth 1 -maxdepth 1 -print -quit)" ] \
  || fail "Artifact restore target is not empty"

# Inspect the named Redis volume without starting Redis. Restore never overwrites AOF/RDB state.
compose run --rm --no-deps --user 0:0 --entrypoint sh redis -ec \
  'test -z "$(find /data -mindepth 1 -maxdepth 1 -print -quit)"' \
  >/dev/null || fail "Redis restore target is not empty"

compose up --detach --wait postgres >/dev/null
STARTED_SERVICES="postgres"
DATABASE_OBJECTS=$(pg_query "SELECT COUNT(*) FROM information_schema.tables
 WHERE table_schema NOT IN ('pg_catalog', 'information_schema');" \
  | tr -d '[:space:]')
[ "$DATABASE_OBJECTS" = 0 ] || fail "PostgreSQL restore target is not empty"

compose cp "$PAYLOAD/postgres.dump" postgres:/tmp/crewscope-restore.dump >/dev/null
compose exec -T postgres pg_restore --username crewscope --dbname crewscope \
  --exit-on-error --no-owner --no-privileges /tmp/crewscope-restore.dump
compose exec -T --user 0 postgres rm -f /tmp/crewscope-restore.dump
[ "$(schema_version)" = "$SOURCE_SCHEMA" ] \
  || fail "restored PostgreSQL Schema Version does not match the Manifest"

validate_archive_entries "$PAYLOAD/artifacts.tar.gz"
tar -xzf "$PAYLOAD/artifacts.tar.gz" -C "$ARTIFACT_ROOT"
node "$RECOVERY_TOOL" relocate-artifacts "$ARTIFACT_ROOT" \
  >"$STAGING/relocated-artifact-verification.json"
node "$RECOVERY_TOOL" verify-artifacts "$ARTIFACT_ROOT" \
  >"$STAGING/restored-artifact-verification.json"

compose run --rm --no-deps --user 0:0 --entrypoint sh \
  -v "$PAYLOAD:/restore:ro" redis -ec '
    cp /restore/redis.rdb /data/dump.rdb
    chown redis:redis /data/dump.rdb
    chmod 600 /data/dump.rdb
  ' >/dev/null
compose up --detach --wait redis >/dev/null
STARTED_SERVICES=$(printf '%s\n%s\n' "$STARTED_SERVICES" redis)

# API starts without Web or Worker, so Flyway can move V26..V32 to V32 while traffic and Claim
# remain closed. A lower target or an incompatible application image fails before traffic opens.
compose up --detach --wait api >/dev/null
STARTED_SERVICES=$(printf '%s\n%s\n' "$STARTED_SERVICES" api)
RESTORED_SCHEMA=$(schema_version)
[ "$RESTORED_SCHEMA" = "$CREWSCOPE_RESTORE_TARGET_SCHEMA" ] \
  || fail "application migration ended at V$RESTORED_SCHEMA instead of V$CREWSCOPE_RESTORE_TARGET_SCHEMA"

compose exec -T api wget -q --spider http://127.0.0.1:8080/actuator/health/readiness
SYSTEM_INFO=$(compose exec -T api wget -qO- http://127.0.0.1:8080/api/v1/system/info)
printf '%s' "$SYSTEM_INFO" | grep -F 'AgentScope Java' >/dev/null \
  || fail "API smoke response does not identify AgentScope Java"
assert_zero_activity || fail "restored target contains active execution state"

if [ "$ENABLE_TRAFFIC" = true ]; then
  compose up --detach --wait worker web >/dev/null
  STARTED_SERVICES=$(printf '%s\n%s\n%s\n' "$STARTED_SERVICES" worker web)
fi

FINISHED_EPOCH=$(date +%s)
FINISHED_AT=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
RTO_SECONDS=$(( FINISHED_EPOCH - START_EPOCH ))
RPO_SECONDS=$(node -e 'const [created,now]=process.argv.slice(1); console.log(Math.floor((Date.parse(now)-Date.parse(created))/1000))' \
  "$CREATED_AT" "$STARTED_AT")
[ "$RPO_SECONDS" -ge 0 ] && [ "$RPO_SECONDS" -le 86400 ] \
  || fail "actual RPO is outside the 24-hour boundary"
[ "$RTO_SECONDS" -le 14400 ] || fail "actual RTO exceeds four hours"

EVIDENCE_DIR="$CREWSCOPE_BACKUP_ROOT/restore-evidence"
mkdir -p "$EVIDENCE_DIR"
EVIDENCE="$EVIDENCE_DIR/$BACKUP_ID-$(date -u '+%Y%m%dT%H%M%SZ').json"
jq -n \
  --arg backupId "$BACKUP_ID" \
  --arg startedAt "$STARTED_AT" \
  --arg finishedAt "$FINISHED_AT" \
  --argjson rpoSeconds "$RPO_SECONDS" \
  --argjson rtoSeconds "$RTO_SECONDS" \
  --argjson sourceSchemaVersion "$SOURCE_SCHEMA" \
  --argjson restoredSchemaVersion "$RESTORED_SCHEMA" \
  --arg environmentFingerprint "$(jq -r '.environmentFingerprint' "$STAGING/verified-manifest.json")" \
  --argjson trafficEnabled "$ENABLE_TRAFFIC" \
  --arg artifactVerification "$(cat "$STAGING/restored-artifact-verification.json")" \
  '{formatVersion:1,backupId:$backupId,startedAt:$startedAt,finishedAt:$finishedAt,
    actualRpoSeconds:$rpoSeconds,actualRtoSeconds:$rtoSeconds,
    sourceSchemaVersion:$sourceSchemaVersion,restoredSchemaVersion:$restoredSchemaVersion,
    environmentFingerprint:$environmentFingerprint,trafficEnabled:$trafficEnabled,
    smoke:{apiReadiness:"UP",systemInfo:"AgentScope Java",activeExecutions:0,
      artifactVerification:($artifactVerification|fromjson)}}' >"$EVIDENCE"
chmod 600 "$EVIDENCE"

printf 'backupId=%s\n' "$BACKUP_ID"
printf 'sourceSchemaVersion=%s\n' "$SOURCE_SCHEMA"
printf 'restoredSchemaVersion=%s\n' "$RESTORED_SCHEMA"
printf 'actualRpoSeconds=%s\n' "$RPO_SECONDS"
printf 'actualRtoSeconds=%s\n' "$RTO_SECONDS"
printf 'trafficEnabled=%s\n' "$ENABLE_TRAFFIC"
printf 'evidence=%s\n' "$EVIDENCE"
