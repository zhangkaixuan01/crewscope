#!/usr/bin/env sh

# Shared Team Beta operator functions. This file is sourced by the backup and restore entrypoints.
set -eu

OPERATIONS_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
TEAM_BETA_DIR=$(CDPATH= cd -- "$OPERATIONS_DIR/.." && pwd)
REPOSITORY_ROOT=$(CDPATH= cd -- "$TEAM_BETA_DIR/../.." && pwd)
RECOVERY_TOOL="$REPOSITORY_ROOT/scripts/team-beta-recovery.mjs"
TEMP_ROOT=${TMPDIR:-/tmp}
TEMP_ROOT=${TEMP_ROOT%/}

fail() {
  printf 'Team Beta operation failed: %s\n' "$*" >&2
  exit 2
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "required command is unavailable: $1"
}

require_absolute_path() {
  case "$1" in
    /*) ;;
    *) fail "$2 must be an absolute path" ;;
  esac
}

require_regular_file() {
  [ -f "$1" ] || fail "$2 is not a regular file: $1"
}

load_operator_file() {
  OPERATOR_ENV_FILE=${1:-}
  require_regular_file "$OPERATOR_ENV_FILE" "operator environment file"
  require_absolute_path "$OPERATOR_ENV_FILE" "operator environment file"

  # The environment file is a protected operator-controlled input and must contain shell-compatible
  # KEY=value entries. Secret values remain in external files referenced by these coordinates.
  set -a
  # shellcheck disable=SC1090
  . "$OPERATOR_ENV_FILE"
  set +a
}

load_operator_environment() {
  load_operator_file "$1"

  : "${CREWSCOPE_DATA_ROOT:?CREWSCOPE_DATA_ROOT is required}"
  : "${CREWSCOPE_SECRETS_ROOT:?CREWSCOPE_SECRETS_ROOT is required}"
  : "${CREWSCOPE_BACKUP_ROOT:?CREWSCOPE_BACKUP_ROOT is required}"
  : "${CREWSCOPE_BACKUP_PASSPHRASE_FILE:?CREWSCOPE_BACKUP_PASSPHRASE_FILE is required}"
  : "${CREWSCOPE_BACKEND_IMAGE:?CREWSCOPE_BACKEND_IMAGE is required}"
  : "${CREWSCOPE_WEB_IMAGE:?CREWSCOPE_WEB_IMAGE is required}"
  : "${CREWSCOPE_APPLICATION_VERSION:?CREWSCOPE_APPLICATION_VERSION is required}"
  : "${CREWSCOPE_DATASET_VERSION:?CREWSCOPE_DATASET_VERSION is required}"
  : "${CREWSCOPE_DATASET_SEED:?CREWSCOPE_DATASET_SEED is required}"

  CREWSCOPE_COMPOSE_PROJECT=${CREWSCOPE_COMPOSE_PROJECT:-crewscope-team-beta}
  CREWSCOPE_COMPOSE_PROFILE=${CREWSCOPE_COMPOSE_PROFILE:-}
  CREWSCOPE_COMPOSE_OVERLAY=${CREWSCOPE_COMPOSE_OVERLAY:-}
  CREWSCOPE_RESTORE_MIN_SCHEMA=${CREWSCOPE_RESTORE_MIN_SCHEMA:-26}
  CREWSCOPE_RESTORE_MAX_SCHEMA=${CREWSCOPE_RESTORE_MAX_SCHEMA:-33}
  CREWSCOPE_RESTORE_TARGET_SCHEMA=${CREWSCOPE_RESTORE_TARGET_SCHEMA:-33}

  for path in "$CREWSCOPE_DATA_ROOT" "$CREWSCOPE_SECRETS_ROOT" \
      "$CREWSCOPE_BACKUP_ROOT" "$CREWSCOPE_BACKUP_PASSPHRASE_FILE"; do
    require_absolute_path "$path" "Team Beta operator coordinate"
  done
  for directory in "$CREWSCOPE_DATA_ROOT" "$CREWSCOPE_SECRETS_ROOT" "$CREWSCOPE_BACKUP_ROOT"; do
    [ "$directory" != / ] || fail "Team Beta directory coordinates must not be the filesystem root"
  done
  [ "$CREWSCOPE_RESTORE_MIN_SCHEMA" = 26 ] \
    && [ "$CREWSCOPE_RESTORE_MAX_SCHEMA" = 33 ] \
    && [ "$CREWSCOPE_RESTORE_TARGET_SCHEMA" = 33 ] \
    || fail "Team Beta recovery boundary is frozen at V26..V33 -> V33"
  require_regular_file "$CREWSCOPE_BACKUP_PASSPHRASE_FILE" "backup passphrase file"
  [ "$(wc -c <"$CREWSCOPE_BACKUP_PASSPHRASE_FILE" | tr -d ' ')" -ge 32 ] \
    || fail "backup passphrase must contain at least 32 bytes"

  require_command docker
  require_command jq
  require_command node
  require_command openssl
  require_command tar
  docker info >/dev/null 2>&1 || fail "Docker Engine is unavailable"
}

compose() {
  if [ -n "$CREWSCOPE_COMPOSE_OVERLAY" ]; then
    require_absolute_path "$CREWSCOPE_COMPOSE_OVERLAY" "Compose overlay"
    if [ -n "$CREWSCOPE_COMPOSE_PROFILE" ]; then
      docker compose --env-file "$OPERATOR_ENV_FILE" -p "$CREWSCOPE_COMPOSE_PROJECT" \
        --profile "$CREWSCOPE_COMPOSE_PROFILE" -f "$TEAM_BETA_DIR/compose.yaml" \
        -f "$CREWSCOPE_COMPOSE_OVERLAY" "$@"
    else
      docker compose --env-file "$OPERATOR_ENV_FILE" -p "$CREWSCOPE_COMPOSE_PROJECT" \
        -f "$TEAM_BETA_DIR/compose.yaml" -f "$CREWSCOPE_COMPOSE_OVERLAY" "$@"
    fi
  elif [ -n "$CREWSCOPE_COMPOSE_PROFILE" ]; then
    docker compose --env-file "$OPERATOR_ENV_FILE" -p "$CREWSCOPE_COMPOSE_PROJECT" \
      --profile "$CREWSCOPE_COMPOSE_PROFILE" -f "$TEAM_BETA_DIR/compose.yaml" "$@"
  else
    docker compose --env-file "$OPERATOR_ENV_FILE" -p "$CREWSCOPE_COMPOSE_PROJECT" \
      -f "$TEAM_BETA_DIR/compose.yaml" "$@"
  fi
}

pg_query() {
  compose exec -T postgres psql --username crewscope --dbname crewscope \
    --no-psqlrc --tuples-only --no-align --set ON_ERROR_STOP=1 --command "$1"
}

schema_version() {
  pg_query "SELECT version FROM crewscope.flyway_schema_history WHERE success = TRUE ORDER BY installed_rank DESC LIMIT 1;" \
    | tr -d '[:space:]'
}

active_execution_counts() {
  task_count=$(pg_query "SELECT COUNT(*) FROM crewscope.task_execution
    WHERE status IN ('CLAIMED','PREPARING','RUNNING','PAUSE_REQUESTED','RECOVERING','CANCEL_REQUESTED','MANUAL_TAKEOVER');" \
    | tr -d '[:space:]')
  action_count=$(pg_query "SELECT COUNT(*) FROM crewscope.action_dispatch
    WHERE status IN ('RUNNING','UNKNOWN','RECONCILING');" | tr -d '[:space:]')
  if [ -n "$(pg_query "SELECT to_regclass('crewscope.notification_delivery');" | tr -d '[:space:]')" ]; then
    notification_count=$(pg_query "SELECT COUNT(*) FROM crewscope.notification_delivery
      WHERE status IN ('RUNNING','UNKNOWN','RECONCILING');" | tr -d '[:space:]')
  else
    # Notification persistence starts after V26, so a V26 source has no notification activity.
    notification_count=0
  fi
  printf 'task=%s\naction=%s\nnotification=%s\n' \
    "$task_count" "$action_count" "$notification_count"
}

count_value() {
  printf '%s\n' "$1" | sed -n "s/^$2=//p" | tr -d '[:space:]'
}

assert_zero_activity() {
  counts=$(active_execution_counts)
  TASK_ACTIVE=$(count_value "$counts" task)
  ACTION_ACTIVE=$(count_value "$counts" action)
  NOTIFICATION_ACTIVE=$(count_value "$counts" notification)
  [ "$TASK_ACTIVE" = 0 ] && [ "$ACTION_ACTIVE" = 0 ] && [ "$NOTIFICATION_ACTIVE" = 0 ] \
    || return 1
}

running_service() {
  compose ps --status running --services | grep -Fx "$1" >/dev/null 2>&1
}

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

safe_remove_temporary() {
  case "${1:-}" in
    "$TEMP_ROOT"/crewscope-team-beta-*) rm -rf -- "$1" ;;
    *) fail "refusing to remove an unrecognized temporary path: ${1:-missing}" ;;
  esac
}

validate_archive_entries() {
  tar -tzf "$1" | awk '
    /^\// { exit 2 }
    /(^|\/)\.\.($|\/)/ { exit 2 }
    { count += 1 }
    END { if (count == 0) exit 2 }
  ' || fail "backup archive contains an unsafe or empty path set"
  tar -tvzf "$1" | awk '
    substr($0, 1, 1) !~ /[-d]/ { exit 2 }
    { count += 1 }
    END { if (count == 0) exit 2 }
  ' || fail "backup archive contains links or special filesystem entries"
}

available_credential_key_ids() {
  require_regular_file "$CREWSCOPE_SECRETS_ROOT/credential_keys" "credential key ring"
  require_regular_file "$CREWSCOPE_SECRETS_ROOT/activity_cursor_key" "activity cursor key"
  require_regular_file "$CREWSCOPE_SECRETS_ROOT/task_token_key" "task token key"
  sed -n 's/^\([^=#][^=]*\)=.*$/credential:\1/p' "$CREWSCOPE_SECRETS_ROOT/credential_keys"
  printf 'activity-cursor:%s\n' "${CREWSCOPE_TEAM_ACTIVITY_CURSOR_CURRENT_KEY_ID:-v1}"
  printf 'task-token:%s\n' "${CREWSCOPE_TASK_TOKEN_CURRENT_KEY_ID:-v1}"
}

verify_required_key_ids() {
  manifest_file=$1
  available=$(available_credential_key_ids | sort -u)
  required=$(jq -r '.credentialKeyIds[]' "$manifest_file" | sort -u)
  missing=$(printf '%s\n' "$required" | while IFS= read -r key_id; do
    printf '%s\n' "$available" | grep -Fx "$key_id" >/dev/null 2>&1 || printf '%s\n' "$key_id"
  done)
  [ -z "$missing" ] || fail "required Credential Key IDs are unavailable: $(printf '%s' "$missing" | tr '\n' ' ')"
}
