#!/usr/bin/env sh
set -eu

OPERATIONS_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
. "$OPERATIONS_DIR/common.sh"

usage() { printf 'Usage: %s /absolute/path/team-beta.env [--prometheus /absolute/path/metrics.prom | --prometheus-default]\n' "$0"; }
[ "$#" -ge 1 ] && [ "$#" -le 3 ] || { usage; exit 2; }
load_operator_file "$1"
: "${CREWSCOPE_BACKUP_ROOT:?CREWSCOPE_BACKUP_ROOT is required}"
require_absolute_path "$CREWSCOPE_BACKUP_ROOT" "backup root"

METRICS_FILE=''
if [ "$#" -eq 2 ]; then
  [ "$2" = '--prometheus-default' ] || fail "the two-argument form requires --prometheus-default"
  : "${CREWSCOPE_DATA_ROOT:?CREWSCOPE_DATA_ROOT is required for --prometheus-default}"
  require_absolute_path "$CREWSCOPE_DATA_ROOT" "data root"
  METRICS_FILE="$CREWSCOPE_DATA_ROOT/metrics/crewscope_backup.prom"
elif [ "$#" -eq 3 ]; then
  [ "$2" = '--prometheus' ] || fail "the only optional flag is --prometheus <file>"
  METRICS_FILE=$3
  require_absolute_path "$METRICS_FILE" "Prometheus metrics file"
fi

now=$(date +%s)
latest=0
if [ -d "$CREWSCOPE_BACKUP_ROOT/daily" ]; then
  for bundle in "$CREWSCOPE_BACKUP_ROOT"/daily/*.bundle.enc; do
    [ -f "$bundle" ] || continue
    modified=$(stat -c '%Y' "$bundle" 2>/dev/null || stat -f '%m' "$bundle")
    [ "$modified" -gt "$latest" ] && latest=$modified
  done
fi
[ "$latest" -gt 0 ] || fail "no daily backup bundle found"
age=$((now - latest))
[ "$age" -ge 0 ] || fail "latest backup timestamp is in the future"

if [ -n "$METRICS_FILE" ]; then
  directory=$(dirname -- "$METRICS_FILE")
  mkdir -p "$directory"
  temporary="$METRICS_FILE.$$"
  {
    printf '# HELP crewscope_backup_age_seconds Age of the newest daily encrypted backup.\n'
    printf '# TYPE crewscope_backup_age_seconds gauge\n'
    printf 'crewscope_backup_age_seconds %s\n' "$age"
    printf '# HELP crewscope_backup_last_success_timestamp_seconds Unix timestamp of newest daily backup.\n'
    printf '# TYPE crewscope_backup_last_success_timestamp_seconds gauge\n'
    printf 'crewscope_backup_last_success_timestamp_seconds %s\n' "$latest"
  } >"$temporary"
  chmod 0644 "$temporary"
  mv "$temporary" "$METRICS_FILE"
fi
printf 'latestDailyBackupEpoch=%s\nageSeconds=%s\n' "$latest" "$age"
