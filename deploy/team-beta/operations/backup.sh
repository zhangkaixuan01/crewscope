#!/usr/bin/env sh
set -eu

OPERATIONS_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# shellcheck disable=SC1091
. "$OPERATIONS_DIR/common.sh"

usage() {
  printf 'Usage: %s /absolute/path/team-beta.env [daily|weekly|release|on-demand]\n' "$0"
}

[ "$#" -ge 1 ] && [ "$#" -le 2 ] || { usage; exit 2; }
load_operator_environment "$1"
BACKUP_CLASS=${2:-on-demand}
case "$BACKUP_CLASS" in
  daily|weekly|release|on-demand) ;;
  *) fail "backup class must be daily, weekly, release or on-demand" ;;
esac

require_command gzip
if [ -z "${CREWSCOPE_GIT_REVISION:-}" ]; then
  require_command git
fi
mkdir -p "$CREWSCOPE_BACKUP_ROOT/$BACKUP_CLASS"
LOCK_DIR="$CREWSCOPE_BACKUP_ROOT/.backup.lock"
mkdir "$LOCK_DIR" 2>/dev/null || fail "another Team Beta backup is active"

STAGING=$(mktemp -d "$TEMP_ROOT/crewscope-team-beta-backup.XXXXXX")
PAYLOAD="$STAGING/payload"
mkdir "$PAYLOAD"
STOPPED_SERVICES=''
FINAL_BUNDLE=''
FINAL_ENVELOPE=''
PUBLISH_STARTED=false
PUBLISHED=false

restart_services() {
  for service in api worker web; do
    printf '%s\n' "$STOPPED_SERVICES" | grep -Fx "$service" >/dev/null 2>&1 || continue
    compose up --detach "$service" >/dev/null || true
  done
}

cleanup() {
  if [ "$PUBLISH_STARTED" = true ] && [ "$PUBLISHED" = false ]; then
    [ -z "$FINAL_BUNDLE" ] || rm -f -- "$FINAL_BUNDLE"
    [ -z "$FINAL_ENVELOPE" ] || rm -f -- "$FINAL_ENVELOPE"
  fi
  restart_services
  rmdir "$LOCK_DIR" 2>/dev/null || true
  safe_remove_temporary "$STAGING"
}
trap cleanup EXIT HUP INT TERM

running_service postgres || fail "PostgreSQL must be running before backup"
running_service redis || fail "Redis must be running before backup"

# Close ingress first. Existing worker activity may drain, but no new browser command can enter.
if running_service web; then
  compose stop --timeout 30 web >/dev/null
  STOPPED_SERVICES="web"
fi

DRAIN_TIMEOUT=${CREWSCOPE_BACKUP_DRAIN_TIMEOUT_SECONDS:-300}
case "$DRAIN_TIMEOUT" in *[!0-9]*|'') fail "backup drain timeout must be a positive integer" ;; esac
[ "$DRAIN_TIMEOUT" -gt 0 ] || fail "backup drain timeout must be positive"
deadline=$(( $(date +%s) + DRAIN_TIMEOUT ))
until assert_zero_activity; do
  [ "$(date +%s)" -lt "$deadline" ] \
    || fail "active TaskExecution, Action Dispatch or Notification Dispatch did not drain"
  sleep 2
done

# Stop claim owners immediately after observing quiescence, then re-check the database to close
# the final claim race before capturing any authoritative component.
if running_service worker; then
  compose stop --timeout 45 worker >/dev/null
  STOPPED_SERVICES=$(printf '%s\n%s\n' "$STOPPED_SERVICES" worker)
fi
if running_service api; then
  compose stop --timeout 45 api >/dev/null
  STOPPED_SERVICES=$(printf '%s\n%s\n' "$STOPPED_SERVICES" api)
fi
assert_zero_activity || fail "activity appeared while entering Maintenance Mode"

SCHEMA_VERSION=$(schema_version)
case "$SCHEMA_VERSION" in *[!0-9]*|'') fail "could not resolve the Flyway Schema Version" ;; esac
[ "$SCHEMA_VERSION" -ge "$CREWSCOPE_RESTORE_MIN_SCHEMA" ] \
  && [ "$SCHEMA_VERSION" -le "$CREWSCOPE_RESTORE_MAX_SCHEMA" ] \
  || fail "Schema V$SCHEMA_VERSION is outside the supported backup boundary"

compose exec -T postgres pg_dump --username crewscope --dbname crewscope \
  --format custom --compress 9 --no-owner --no-privileges >"$PAYLOAD/postgres.dump"

ARTIFACT_ROOT="$CREWSCOPE_DATA_ROOT/artifacts"
[ -d "$ARTIFACT_ROOT" ] || fail "Artifact root is missing: $ARTIFACT_ROOT"
node "$RECOVERY_TOOL" verify-artifacts "$ARTIFACT_ROOT" >"$PAYLOAD/artifact-verification.json"
tar -czf "$PAYLOAD/artifacts.tar.gz" -C "$ARTIFACT_ROOT" .

# Redis reads its ACL Secret inside the container. The password never enters the host command line
# or evidence output. SAVE is safe because all application writers are already stopped.
compose exec -T redis sh -ec '
  password=$(sed -n "s/^user default on >\([^ ]*\).*/\1/p" /run/secrets/redis_acl)
  test -n "$password"
  exec redis-cli --no-auth-warning -a "$password" SAVE
' >/dev/null
compose cp redis:/data/dump.rdb "$PAYLOAD/redis.rdb" >/dev/null

CREATED_AT=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
BACKUP_ID="$(date -u '+%Y%m%dT%H%M%SZ')-$(openssl rand -hex 8)"
GIT_REVISION=${CREWSCOPE_GIT_REVISION:-$(git -C "$REPOSITORY_ROOT" rev-parse HEAD)}

jq -n \
  --arg dataRoot "$CREWSCOPE_DATA_ROOT" \
  --arg gitRevision "$GIT_REVISION" \
  --arg backendImage "$CREWSCOPE_BACKEND_IMAGE" \
  --arg webImage "$CREWSCOPE_WEB_IMAGE" \
  --argjson schemaVersion "$SCHEMA_VERSION" \
  --arg datasetVersion "$CREWSCOPE_DATASET_VERSION" \
  --arg seed "$CREWSCOPE_DATASET_SEED" \
  '{dataRoot:$dataRoot,gitRevision:$gitRevision,backendImage:$backendImage,webImage:$webImage,
    schemaVersion:$schemaVersion,datasetVersion:$datasetVersion,seed:$seed}' \
  >"$STAGING/fingerprint-input.json"
ENVIRONMENT_FINGERPRINT=$(node "$RECOVERY_TOOL" fingerprint \
  "$STAGING/fingerprint-input.json" "$PAYLOAD/environment-fingerprint.json")

CREDENTIAL_KEY_IDS=$(available_credential_key_ids | jq -Rsc 'split("\n") | map(select(length > 0)) | unique')
jq -n \
  --arg applicationVersion "$CREWSCOPE_APPLICATION_VERSION" \
  --arg backupClass "$BACKUP_CLASS" \
  --arg backupId "$BACKUP_ID" \
  --arg createdAt "$CREATED_AT" \
  --arg environmentFingerprint "$ENVIRONMENT_FINGERPRINT" \
  --arg gitRevision "$GIT_REVISION" \
  --argjson schemaVersion "$SCHEMA_VERSION" \
  --argjson credentialKeyIds "$CREDENTIAL_KEY_IDS" \
  '{applicationVersion:$applicationVersion,backupClass:$backupClass,backupId:$backupId,
    createdAt:$createdAt,credentialKeyIds:$credentialKeyIds,
    environmentFingerprint:$environmentFingerprint,gitRevision:$gitRevision,
    maintenance:{ingressStopped:true,apiStopped:true,workerStopped:true,
      activeTaskExecutions:0,activeActionDispatches:0,activeNotificationDispatches:0},
    schemaVersion:$schemaVersion}' >"$STAGING/metadata.json"
node "$RECOVERY_TOOL" create-manifest "$PAYLOAD" "$STAGING/metadata.json" >/dev/null

PLAIN_ARCHIVE="$STAGING/$BACKUP_ID.tar.gz"
tar -czf "$PLAIN_ARCHIVE" -C "$PAYLOAD" .
FINAL_BUNDLE="$CREWSCOPE_BACKUP_ROOT/$BACKUP_CLASS/$BACKUP_ID.bundle.enc"
FINAL_ENVELOPE="$CREWSCOPE_BACKUP_ROOT/$BACKUP_CLASS/$BACKUP_ID.envelope.json"
[ ! -e "$FINAL_BUNDLE" ] && [ ! -e "$FINAL_ENVELOPE" ] \
  || fail "backup output already exists for $BACKUP_ID"
STAGED_BUNDLE="$STAGING/$BACKUP_ID.bundle.enc"
STAGED_ENVELOPE="$STAGING/$BACKUP_ID.envelope.json"
openssl enc -aes-256-cbc -salt -pbkdf2 -iter 200000 -md sha256 \
  -pass "file:$CREWSCOPE_BACKUP_PASSPHRASE_FILE" \
  -in "$PLAIN_ARCHIVE" -out "$STAGED_BUNDLE"
node "$RECOVERY_TOOL" create-envelope "$STAGED_BUNDLE" \
  "$PAYLOAD/manifest.json" "$STAGED_ENVELOPE" >/dev/null
chmod 600 "$STAGED_BUNDLE" "$STAGED_ENVELOPE"
PUBLISH_STARTED=true
mv "$STAGED_ENVELOPE" "$FINAL_ENVELOPE"
# Bundle is the discoverable commit marker used by retention and restore selection. Publishing it
# last prevents an abrupt stop between the two renames from exposing an incomplete backup.
mv "$STAGED_BUNDLE" "$FINAL_BUNDLE"
PUBLISHED=true

printf 'backupId=%s\n' "$BACKUP_ID"
printf 'bundle=%s\n' "$FINAL_BUNDLE"
printf 'envelope=%s\n' "$FINAL_ENVELOPE"
printf 'schemaVersion=%s\n' "$SCHEMA_VERSION"
printf 'environmentFingerprint=%s\n' "$ENVIRONMENT_FINGERPRINT"
