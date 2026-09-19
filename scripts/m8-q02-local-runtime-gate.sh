#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
project_name="${CREWSCOPE_M8_Q02_PROJECT_NAME:-crewscope-m8-q02-local}"
web_port="${CREWSCOPE_M8_Q02_WEB_PORT:-18080}"
temp_root="${TMPDIR:-/tmp}"
temp_root="${temp_root%/}"
runtime_root="$(mktemp -d "$temp_root/crewscope-m8-q02-runtime.XXXXXX")"
snapshot_root="$(mktemp -d "$temp_root/crewscope-m8-q02-snapshot.XXXXXX")"

case "$project_name" in
  ''|[!a-z0-9]*|*[!a-z0-9_-]*)
    echo "CREWSCOPE_M8_Q02_PROJECT_NAME must start with a lowercase letter or number and contain only lowercase letters, numbers, '_' or '-'." >&2
    exit 2
    ;;
esac

cleanup() {
  local status=$?
  set +e
  if [[ "$status" -ne 0 ]]; then
    echo "M8-Q02 runtime gate failed; preserving bounded diagnostics:" >&2
    docker ps -a --filter "label=com.docker.compose.project=$project_name" \
      --format '{{.Names}} {{.Status}}' >&2
  fi
  CREWSCOPE_QUICKSTART_PROJECT_NAME="$project_name" \
  CREWSCOPE_QUICKSTART_RUNTIME_ROOT="$runtime_root" \
  CREWSCOPE_WEB_PORT="$web_port" \
    "$repository_root/deploy/team-beta/quickstart.sh" reset >/dev/null 2>&1 || true
  case "$runtime_root" in
    "$temp_root"/crewscope-m8-q02-runtime.*) rm -rf -- "$runtime_root" ;;
    *) echo "Refusing to remove unexpected runtime path: $runtime_root" >&2 ;;
  esac
  case "$snapshot_root" in
    "$temp_root"/crewscope-m8-q02-snapshot.*) rm -rf -- "$snapshot_root" ;;
  esac
  exit "$status"
}
trap cleanup EXIT

for command in curl docker openssl; do
  command -v "$command" >/dev/null || {
    echo "$command is required for the M8-Q02 local runtime gate." >&2
    exit 1
  }
done
docker info >/dev/null

cd "$repository_root"
export CREWSCOPE_QUICKSTART_PROJECT_NAME="$project_name"
export CREWSCOPE_QUICKSTART_RUNTIME_ROOT="$runtime_root"
export CREWSCOPE_ENVIRONMENT="$project_name"
export CREWSCOPE_WEB_PORT="$web_port"
./deploy/team-beta/quickstart.sh build
CREWSCOPE_QUICKSTART_PROJECT_NAME="$project_name" \
CREWSCOPE_QUICKSTART_RUNTIME_ROOT="$runtime_root" \
CREWSCOPE_WEB_PORT="$web_port" \
  ./deploy/team-beta/quickstart.sh up

container_id() {
  docker ps --filter "label=com.docker.compose.project=$project_name" \
    --filter "label=com.docker.compose.service=$1" --format '{{.ID}}'
}

expected_services=(postgres redis api web)
running_count="$(docker ps --filter "label=com.docker.compose.project=$project_name" --format '{{.ID}}' | wc -l | tr -d ' ')"
[[ "$running_count" == "${#expected_services[@]}" ]] || {
  echo "M8-Q02 runtime expected ${#expected_services[@]} services but found $running_count." >&2
  exit 1
}
for service in "${expected_services[@]}"; do
  id="$(container_id "$service")"
  [[ -n "$id" ]] || { echo "M8-Q02 service is not running: $service" >&2; exit 1; }
  status="$(docker inspect --format '{{.State.Status}}' "$id")"
  health="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}missing{{end}}' "$id")"
  [[ "$status" == running && "$health" == healthy ]] || {
    echo "M8-Q02 service is not healthy: $service status=$status health=$health" >&2
    exit 1
  }
done

curl --fail --silent --show-error "http://127.0.0.1:$web_port/healthz" >/dev/null
curl --fail --silent --show-error "http://127.0.0.1:$web_port/setup" >/dev/null

# Verify the actual application UID can create workspaces in every mounted runtime root.
docker exec "$(container_id api)" sh -ec '
  for root in "$CREWSCOPE_PERSONAL_AGENT_RUNTIME_ROOT" "$CREWSCOPE_TEMPLATE_AGENT_RUNTIME_ROOT" \
      "$CREWSCOPE_TASK_AGENT_RUNTIME_ROOT" "$CREWSCOPE_CODING_AGENT_RUNTIME_ROOT" \
      "$CREWSCOPE_CODING_REPOSITORY_MANAGED_ROOT" "$CREWSCOPE_CODING_WORKTREE_ROOT"; do
    probe=$(mktemp -d "$root/.write-probe.XXXXXX")
    rmdir "$probe"
  done
  docker version --format "{{.Server.Version}}"
'
worker_count=$(docker exec "$(container_id postgres)" psql -U crewscope -d crewscope -Atc \
  "select count(*) from crewscope.runtime_worker where runtime_profile = 'ALL';")
[[ "$worker_count" -ge 1 ]] || { echo "Combined API/Worker did not register." >&2; exit 1; }

# A cold restore must preserve database rows, Redis and every mounted filesystem, not only boot.
docker exec "$(container_id postgres)" psql -U crewscope -d crewscope -c \
  "create table public.snapshot_probe (value text); insert into public.snapshot_probe values ('roundtrip');" >/dev/null
docker exec "$(container_id redis)" redis-cli SET snapshot-probe roundtrip >/dev/null
docker exec "$(container_id api)" sh -ec '
  for root in /var/crewscope/artifacts /var/crewscope/agent-runtime /var/crewscope/template-agent-runtime \
      /var/crewscope/task-agent-runtime /var/crewscope/coding-agent-runtime "$CREWSCOPE_CODING_REPOSITORY_MANAGED_ROOT"; do
    echo roundtrip > "$root/.snapshot-probe"
  done
'
./deploy/team-beta/snapshot.sh backup "$snapshot_root/backup"
if ./deploy/team-beta/snapshot.sh restore "$snapshot_root/backup" >"$snapshot_root/refused.log" 2>&1; then
  echo "Snapshot restore unexpectedly accepted a nonempty target." >&2
  exit 1
fi
if ! grep -q 'Target env already exists' "$snapshot_root/refused.log"; then
  cat "$snapshot_root/refused.log" >&2
  exit 1
fi
./deploy/team-beta/quickstart.sh reset
# Retain the original test runtime for comparison; restore into its now-empty original path.
mv "$runtime_root" "$snapshot_root/original-runtime"
./deploy/team-beta/snapshot.sh restore "$snapshot_root/backup"
cmp "$runtime_root/.env" "$snapshot_root/backup/env"
./deploy/team-beta/quickstart.sh up
[[ "$(docker exec "$(container_id postgres)" psql -U crewscope -d crewscope -Atc 'select value from public.snapshot_probe;')" == roundtrip ]]
[[ "$(docker exec "$(container_id redis)" redis-cli GET snapshot-probe)" == roundtrip ]]
docker exec "$(container_id api)" sh -ec '
  for root in /var/crewscope/artifacts /var/crewscope/agent-runtime /var/crewscope/template-agent-runtime \
      /var/crewscope/task-agent-runtime /var/crewscope/coding-agent-runtime "$CREWSCOPE_CODING_REPOSITORY_MANAGED_ROOT"; do
    test "$(cat "$root/.snapshot-probe")" = roundtrip
  done
'
echo "M8-Q02 four-service runtime and complete cold snapshot/restore gate passed."
