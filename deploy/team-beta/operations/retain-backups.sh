#!/usr/bin/env sh
set -eu

OPERATIONS_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# shellcheck disable=SC1091
. "$OPERATIONS_DIR/common.sh"

usage() {
  printf 'Usage: %s /absolute/path/team-beta.env [--apply]\n' "$0"
}

[ "$#" -ge 1 ] && [ "$#" -le 2 ] || { usage; exit 2; }
load_operator_file "$1"
: "${CREWSCOPE_BACKUP_ROOT:?CREWSCOPE_BACKUP_ROOT is required}"
require_absolute_path "$CREWSCOPE_BACKUP_ROOT" "backup root"
[ "$CREWSCOPE_BACKUP_ROOT" != / ] || fail "backup root must not be the filesystem root"

APPLY=false
if [ "$#" -eq 2 ]; then
  [ "$2" = '--apply' ] || fail "the only optional flag is --apply"
  APPLY=true
fi

[ -d "$CREWSCOPE_BACKUP_ROOT" ] || fail "backup root does not exist: $CREWSCOPE_BACKUP_ROOT"
LOCK_DIR="$CREWSCOPE_BACKUP_ROOT/.retention.lock"
mkdir "$LOCK_DIR" 2>/dev/null || fail "another Team Beta retention run is active"
STAGING=$(mktemp -d "$TEMP_ROOT/crewscope-team-beta-retention.XXXXXX")

cleanup() {
  rmdir "$LOCK_DIR" 2>/dev/null || true
  safe_remove_temporary "$STAGING"
}
trap cleanup EXIT HUP INT TERM

retain_class() {
  backup_class=$1
  keep=$2
  directory="$CREWSCOPE_BACKUP_ROOT/$backup_class"
  [ -d "$directory" ] || return 0
  candidates="$STAGING/$backup_class-candidates"
  : >"$candidates"

  find "$directory" -mindepth 1 -maxdepth 1 -type f -name '*.bundle.enc' -print \
    | LC_ALL=C sort -r >"$candidates"
  while IFS= read -r bundle; do
    [ -n "$bundle" ] || continue
    envelope=${bundle%.bundle.enc}.envelope.json
    require_regular_file "$envelope" "backup envelope paired with $bundle"
  done <"$candidates"

  sed -n "$((keep + 1)),\$p" "$candidates" | while IFS= read -r bundle; do
    [ -n "$bundle" ] || continue
    envelope=${bundle%.bundle.enc}.envelope.json
    if [ "$APPLY" = true ]; then
      rm -- "$bundle" "$envelope"
      printf 'deleted bundle=%s envelope=%s\n' "$bundle" "$envelope"
    else
      printf 'would-delete bundle=%s envelope=%s\n' "$bundle" "$envelope"
    fi
  done
}

retain_class daily 7
retain_class weekly 4
printf 'retentionMode=%s dailyKeep=7 weeklyKeep=4\n' \
  "$(if [ "$APPLY" = true ]; then printf apply; else printf dry-run; fi)"
