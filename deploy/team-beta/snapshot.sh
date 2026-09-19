#!/usr/bin/env sh
set -eu
umask 077

# Cold snapshots for the simple Compose; all volumes, encryption keys and Git paths stay together.
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPOSITORY_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)
PROJECT_NAME="${CREWSCOPE_QUICKSTART_PROJECT_NAME:-${CREWSCOPE_DEMO_PROJECT_NAME:-crewscope-team-beta}}"
RUNTIME_ROOT="${CREWSCOPE_QUICKSTART_RUNTIME_ROOT:-${CREWSCOPE_DEMO_RUNTIME_ROOT:-$SCRIPT_DIR/.runtime}}"
volumes='postgres-data redis-data artifacts agent-runtime template-agent-runtime task-agent-runtime coding-agent-runtime'
action="${1:-}"
snapshot="${2:-}"
fail() { echo "$*" >&2; exit 2; }
. "$SCRIPT_DIR/paths.sh"
case "$action" in backup|restore) ;; *) fail "Usage: $0 backup|restore /absolute/path/to/snapshot" ;; esac
case "$snapshot" in /*) ;; *) fail "Snapshot path must be absolute." ;; esac
case "$RUNTIME_ROOT" in /*) ;; *) fail "Runtime path must be absolute." ;; esac
case "$PROJECT_NAME" in ''|[!a-z0-9]*|*[!a-z0-9_-]*) fail "Invalid Compose project name." ;; esac
assert_dedicated_directory "$RUNTIME_ROOT" "$REPOSITORY_ROOT" "${HOME:-}"
snapshot_physical=$(physical_path "$snapshot")
runtime_physical=$(physical_path "$RUNTIME_ROOT")
docker info >/dev/null
docker image inspect crewscope-backend:local >/dev/null
command -v openssl >/dev/null || fail "OpenSSL is required for snapshot checksums."

manifest() {
  (
    cd "$snapshot"
    for file in env project runtime-root execution-root; do
      openssl dgst -sha256 -r "$file"
    done
    for volume in $volumes; do
      openssl dgst -sha256 -r "$volume.tar.gz"
    done
    openssl dgst -sha256 -r execution.tar.gz
  )
}

if [ "$action" = backup ]; then
  [ -f "$RUNTIME_ROOT/.env" ] || fail "No initialized runtime to back up."
  # Include any newly introduced keys before copying the configuration, never after it.
  "$SCRIPT_DIR/quickstart.sh" init >/dev/null
  execution_root=$(awk -F= '/^CREWSCOPE_EXECUTION_ROOT=/ {print substr($0, index($0, "=")+1); exit}' "$RUNTIME_ROOT/.env")
  assert_dedicated_directory "$execution_root" "$REPOSITORY_ROOT" "$RUNTIME_ROOT" "${HOME:-}"
  [ -d "$execution_root" ] || fail "Execution directory is missing."
  execution_physical=$(physical_path "$execution_root")
  case "$snapshot_physical/" in "$execution_physical/"*|"$runtime_physical/"*) fail "Store snapshots outside the runtime and execution directories." ;; esac
  [ "${CREWSCOPE_EXECUTION_ROOT:-$execution_root}" = "$execution_root" ] || fail "Persist the execution root in .env before backing up."
  for volume in $volumes; do
    docker volume inspect "${PROJECT_NAME}_$volume" >/dev/null
  done
  mkdir "$snapshot" # Refuse to overwrite an existing snapshot.
  printf '%s\n' "$PROJECT_NAME" >"$snapshot/project"
  printf '%s\n' "$RUNTIME_ROOT" >"$snapshot/runtime-root"
  printf '%s\n' "$execution_root" >"$snapshot/execution-root"
  cp "$RUNTIME_ROOT/.env" "$snapshot/env"
  "$SCRIPT_DIR/quickstart.sh" down
  for volume in $volumes; do
    docker run --rm --network none --user 0 --entrypoint /bin/sh \
      --mount "type=volume,src=${PROJECT_NAME}_$volume,dst=/data,readonly" \
      crewscope-backend:local -ec 'tar -C /data -czf - .' >"$snapshot/$volume.tar.gz"
  done
  docker run --rm --network none --user 0 --entrypoint /bin/sh \
    --mount "type=bind,src=$execution_root,dst=/data,readonly" \
    crewscope-backend:local -ec 'tar -C /data -czf - .' >"$snapshot/execution.tar.gz"
  # Compute and verify with the same host-side implementation on macOS and Linux.
  manifest >"$snapshot/SHA256SUMS"
  echo "Snapshot complete: $snapshot. Services remain stopped; run quickstart.sh up when ready."
  exit 0
fi

[ -f "$snapshot/SHA256SUMS" ] || fail "Incomplete snapshot: missing SHA256SUMS."
# Require the complete manifest before touching an empty target, even for a truncated snapshot.
for file in env project runtime-root execution-root execution.tar.gz; do
  [ -f "$snapshot/$file" ] || fail "Snapshot is missing $file."
done
manifest_temp=$(mktemp)
trap 'rm -f "$manifest_temp"' EXIT HUP INT TERM
manifest >"$manifest_temp"
cmp "$manifest_temp" "$snapshot/SHA256SUMS" || fail "Snapshot checksum verification failed."
rm -f "$manifest_temp"
trap - EXIT HUP INT TERM
[ "$(cat "$snapshot/project")" = "$PROJECT_NAME" ] || fail "Restore requires the original Compose project name."
[ "$(cat "$snapshot/runtime-root")" = "$RUNTIME_ROOT" ] || fail "Restore requires the original absolute runtime path."
execution_root=$(cat "$snapshot/execution-root")
assert_dedicated_directory "$execution_root" "$REPOSITORY_ROOT" "$RUNTIME_ROOT" "${HOME:-}"
[ ! -e "$RUNTIME_ROOT/.env" ] && [ ! -L "$RUNTIME_ROOT/.env" ] || fail "Target env already exists; restore only into an empty installation."
snapshot_execution=$(awk -F= '/^CREWSCOPE_EXECUTION_ROOT=/ {print substr($0, index($0, "=")+1); exit}' "$snapshot/env")
[ "$snapshot_execution" = "$execution_root" ] || fail "Snapshot env and execution-root disagree."
if [ -d "$execution_root" ]; then
  [ -z "$(ls -A "$execution_root")" ] || fail "Target execution directory is not empty."
fi
[ -z "$(docker ps -aq --filter "label=com.docker.compose.project=$PROJECT_NAME")" ] || fail "Target project still has containers."
for volume in $volumes; do
  if docker volume inspect "${PROJECT_NAME}_$volume" >/dev/null 2>&1; then
    fail "Target volume already exists: ${PROJECT_NAME}_$volume. Use an empty installation."
  fi
  [ -f "$snapshot/$volume.tar.gz" ] || fail "Snapshot is missing $volume."
done
mkdir -p "$RUNTIME_ROOT" "$execution_root"
chmod 700 "$RUNTIME_ROOT"
cp "$snapshot/env" "$RUNTIME_ROOT/.env"
chmod 600 "$RUNTIME_ROOT/.env"
for volume in $volumes; do
  docker volume create --label "com.docker.compose.project=$PROJECT_NAME" \
    --label "com.docker.compose.volume=$volume" "${PROJECT_NAME}_$volume" >/dev/null
  docker run -i --rm --network none --user 0 --entrypoint /bin/sh \
    --mount "type=volume,src=${PROJECT_NAME}_$volume,dst=/data" \
    crewscope-backend:local -ec 'tar -C /data -xzf -' <"$snapshot/$volume.tar.gz"
done
docker run -i --rm --network none --user 0 --entrypoint /bin/sh \
  --mount "type=bind,src=$execution_root,dst=/data" \
  crewscope-backend:local -ec 'tar -C /data -xzf -' <"$snapshot/execution.tar.gz"
echo "Snapshot restored. Services remain stopped; run quickstart.sh up to verify."
