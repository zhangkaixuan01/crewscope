#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
project_name="${CREWSCOPE_M8_Q02_PROJECT_NAME:-crewscope-m8-q02-local}"
web_port="${CREWSCOPE_M8_Q02_WEB_PORT:-18080}"
temp_root="${TMPDIR:-/tmp}"
temp_root="${temp_root%/}"
runtime_root="$(mktemp -d "$temp_root/crewscope-m8-q02-runtime.XXXXXX")"

case "$project_name" in
  ''|*[!a-zA-Z0-9_-]*)
    echo "CREWSCOPE_M8_Q02_PROJECT_NAME must contain only letters, numbers, '_' or '-'." >&2
    exit 2
    ;;
esac

cleanup() {
  local status=$?
  set +e
  if [[ "$status" -ne 0 ]]; then
    echo "M8-Q02 runtime gate failed; preserving bounded API/Worker diagnostics:" >&2
    docker ps -a \
      --filter "label=com.docker.compose.project=$project_name" \
      --format '{{.Names}} {{.Status}}' >&2
    for service in api worker; do
      id="$(docker ps -a \
        --filter "label=com.docker.compose.project=$project_name" \
        --filter "label=com.docker.compose.service=$service" \
        --format '{{.ID}}' | head -1)"
      if [[ -n "$id" ]]; then
        echo "--- $service logs (last 200 lines) ---" >&2
        docker logs --tail 200 "$id" >&2
      fi
    done
  fi
  CREWSCOPE_DEMO_PROJECT_NAME="$project_name" \
  CREWSCOPE_DEMO_RUNTIME_ROOT="$runtime_root" \
  CREWSCOPE_WEB_PORT="$web_port" \
    "$repository_root/deploy/team-beta/demo.sh" reset >/dev/null 2>&1 || true
  case "$runtime_root" in
    "$temp_root"/crewscope-m8-q02-runtime.*) rm -rf -- "$runtime_root" ;;
    *) echo "Refusing to remove unexpected runtime path: $runtime_root" >&2 ;;
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
docker image inspect crewscope-backend:demo >/dev/null
docker image inspect crewscope-web:demo >/dev/null

cd "$repository_root"
CREWSCOPE_DEMO_PROJECT_NAME="$project_name" \
CREWSCOPE_DEMO_RUNTIME_ROOT="$runtime_root" \
CREWSCOPE_DEMO_BUILD=false \
CREWSCOPE_WEB_PORT="$web_port" \
  ./deploy/team-beta/demo.sh up

container_id() {
  docker ps \
    --filter "label=com.docker.compose.project=$project_name" \
    --filter "label=com.docker.compose.service=$1" \
    --format '{{.ID}}'
}

expected_services=(
  docker-socket-proxy postgres redis otel-collector prometheus alertmanager backup-metrics api worker web
)
running_count="$(docker ps --filter "label=com.docker.compose.project=$project_name" --format '{{.ID}}' | wc -l | tr -d ' ')"
[[ "$running_count" == "${#expected_services[@]}" ]] || {
  echo "M8-Q02 runtime expected ${#expected_services[@]} services but found $running_count." >&2
  exit 1
}
for service in "${expected_services[@]}"; do
  id="$(container_id "$service")"
  [[ -n "$id" ]] || {
    echo "M8-Q02 runtime service is not running: $service" >&2
    exit 1
  }
  status="$(docker inspect --format '{{.State.Status}}' "$id")"
  health="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}missing{{end}}' "$id")"
  [[ "$status" == running && "$health" == healthy ]] || {
    echo "M8-Q02 runtime service is not healthy: $service status=$status health=$health" >&2
    exit 1
  }
done

worker_id="$(container_id worker)"
proxy_id="$(container_id docker-socket-proxy)"
worker_mounts="$(docker inspect --format '{{range .Mounts}}{{println .Source "->" .Destination}}{{end}}' "$worker_id")"
proxy_mounts="$(docker inspect --format '{{range .Mounts}}{{println .Source "->" .Destination}}{{end}}' "$proxy_id")"
if grep -F '/var/run/docker.sock' <<<"$worker_mounts" >/dev/null; then
  echo "Worker must not mount the host Docker socket." >&2
  exit 1
fi
grep -F '/var/run/docker.sock -> /var/run/docker.sock' <<<"$proxy_mounts" >/dev/null || {
  echo "Docker Socket Proxy must be the host socket owner." >&2
  exit 1
}

docker exec "$worker_id" docker ps --format '{{.ID}}' >/dev/null
if docker exec "$worker_id" docker volume ls >/dev/null 2>&1; then
  echo "Worker unexpectedly obtained Docker Volume management access." >&2
  exit 1
fi

prometheus_id="$(container_id prometheus)"
alertmanager_id="$(container_id alertmanager)"
backup_metrics_id="$(container_id backup-metrics)"
docker exec "$prometheus_id" promtool check rules /etc/prometheus/alerts.yaml >/dev/null
docker exec "$alertmanager_id" amtool check-config /etc/alertmanager/alertmanager.yml >/dev/null
docker exec "$backup_metrics_id" wget -qO- http://127.0.0.1:9100/metrics >/dev/null
curl --fail --silent --show-error "http://127.0.0.1:$web_port/healthz" >/dev/null
curl --fail --silent --show-error "http://127.0.0.1:$web_port/setup" >/dev/null

echo "M8-Q02 isolated ten-service runtime gate passed."
echo "Worker Docker API allow-list passed: container access allowed, volume access denied."
