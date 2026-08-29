#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPOSITORY_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)
PROJECT_NAME="${CREWSCOPE_DEMO_PROJECT_NAME:-crewscope-team-beta}"
RUNTIME_ROOT="${CREWSCOPE_DEMO_RUNTIME_ROOT:-$SCRIPT_DIR/.runtime}"
DATA_ROOT="$RUNTIME_ROOT/data"
SECRETS_ROOT="$RUNTIME_ROOT/secrets"
COMPOSE_FILES="-f $SCRIPT_DIR/compose.yaml -f $SCRIPT_DIR/compose.demo.yaml"
ALPINE_IMAGE="${CREWSCOPE_DEMO_HELPER_IMAGE:-alpine:3.22@sha256:14358309a308569c32bdc37e2e0e9694be33a9d99e68afb0f5ff33cc1f695dce}"

usage() {
  echo "Usage: $0 up|down|reset|status|logs|set-registration-mode [OPEN|INVITE_ONLY|DISABLED]"
}

validate_isolation() {
  case "$PROJECT_NAME" in
    ''|*[!a-zA-Z0-9_-]*)
      echo "CREWSCOPE_DEMO_PROJECT_NAME must contain only letters, numbers, '_' or '-'" >&2
      exit 2
      ;;
  esac
  case "$RUNTIME_ROOT" in
    /*) ;;
    *)
      echo "CREWSCOPE_DEMO_RUNTIME_ROOT must be an absolute path" >&2
      exit 2
      ;;
  esac
}

socket_group() {
  if stat -c '%g' /var/run/docker.sock >/dev/null 2>&1; then
    stat -c '%g' /var/run/docker.sock
  else
    stat -f '%g' /var/run/docker.sock
  fi
}

write_secret() {
  target="$1"
  value="$2"
  if [ ! -f "$target" ]; then
    printf '%s\n' "$value" >"$target"
    chmod 600 "$target"
  fi
}

prepare_runtime() {
  command -v docker >/dev/null
  command -v openssl >/dev/null
  docker info >/dev/null
  mkdir -p "$SECRETS_ROOT" \
    "$DATA_ROOT/artifacts" "$DATA_ROOT/github-mirrors" \
    "$DATA_ROOT/repositories" "$DATA_ROOT/worktrees" \
    "$DATA_ROOT/worktree-locks" "$DATA_ROOT/runtime"
  chmod 700 "$SECRETS_ROOT"

  database_password=$(openssl rand -hex 24)
  bootstrap_password=$(openssl rand -hex 24)
  monitoring_password=$(openssl rand -hex 24)
  redis_password=$(openssl rand -hex 24)
  credential_key=$(openssl rand -base64 32 | tr -d '\n')
  activity_key=$(openssl rand -base64 32 | tr -d '\n')
  task_key=$(openssl rand -base64 32 | tr -d '\n')
  diff_secret=$(openssl rand -hex 32)
  login_defense_key=$(openssl rand -base64 32 | tr -d '\n')
  invitation_token_key=$(openssl rand -base64 32 | tr -d '\n')

  write_secret "$SECRETS_ROOT/database_password" "$database_password"
  write_secret "$SECRETS_ROOT/bootstrap_password" "$bootstrap_password"
  write_secret "$SECRETS_ROOT/monitoring_password" "$monitoring_password"
  write_secret "$SECRETS_ROOT/credential_keys" "v1=$credential_key"
  write_secret "$SECRETS_ROOT/activity_cursor_key" "$activity_key"
  write_secret "$SECRETS_ROOT/task_token_key" "$task_key"
  write_secret "$SECRETS_ROOT/diff_cursor_secret" "$diff_secret"
  write_secret "$SECRETS_ROOT/login_defense_hmac_key" "$login_defense_key"
  write_secret "$SECRETS_ROOT/invitation_token_hmac_key" "$invitation_token_key"
  write_secret "$SECRETS_ROOT/redis_url" "redis://default:$redis_password@redis:6379"
  if [ ! -f "$SECRETS_ROOT/redis_acl" ]; then
    {
      printf 'user default on >%s ~* &* +@all\n' "$redis_password"
      printf 'user health on nopass -@all +ping\n'
    } >"$SECRETS_ROOT/redis_acl"
    chmod 600 "$SECRETS_ROOT/redis_acl"
  fi

  # File-backed Secrets keep host ownership on Linux. Retain the local owner while granting the
  # fixed backend runtime group read-only access; Redis stages its owner-only ACL in container tmpfs.
  docker run --rm -v "$SECRETS_ROOT:/secrets" "$ALPINE_IMAGE" sh -ec '
    for name in database_password bootstrap_password monitoring_password credential_keys \
        activity_cursor_key diff_cursor_secret task_token_key redis_url \
        login_defense_hmac_key invitation_token_hmac_key; do
      chgrp 10001 "/secrets/$name"
      chmod 0440 "/secrets/$name"
    done
    chmod 0600 /secrets/redis_acl
  '

  # Bind sources keep the same absolute path inside Worker and sibling Sandbox containers. A
  # one-shot pinned helper assigns the fixed image UID without adding an eighth long-running service.
  docker run --rm -v "$DATA_ROOT:$DATA_ROOT" "$ALPINE_IMAGE" \
    chown -R 10001:10001 "$DATA_ROOT"
}

export_deployment_environment() {
  export CREWSCOPE_BACKEND_IMAGE=crewscope-backend:demo
  export CREWSCOPE_WEB_IMAGE=crewscope-web:demo
  export CREWSCOPE_DATA_ROOT="$DATA_ROOT"
  export CREWSCOPE_SECRETS_ROOT="$SECRETS_ROOT"
  export CREWSCOPE_DOCKER_GID="$(socket_group)"
  export CREWSCOPE_WEB_PORT="${CREWSCOPE_WEB_PORT:-8080}"
  export CREWSCOPE_BOOTSTRAP_ORGANIZATION_ID=0198a475-0831-7000-8000-000000000001
  export CREWSCOPE_BOOTSTRAP_RUNTIME_PRINCIPAL_ID=0198a475-0831-7000-8000-000000000002
  export CREWSCOPE_BOOTSTRAP_ORGANIZATION_NAME="CrewScope Team Beta"
}

action="${1:-}"
validate_isolation
case "$action" in
  up)
    prepare_runtime
    export_deployment_environment
    cd "$REPOSITORY_ROOT"
    if [ "${CREWSCOPE_DEMO_BUILD:-true}" = true ]; then
      # shellcheck disable=SC2086
      docker compose --project-name "$PROJECT_NAME" --profile demo $COMPOSE_FILES \
        up --detach --build --wait
    else
      # Release sub-gates may reuse images already built by their enclosing Release Gate. Compose
      # still fails closed when either explicit demo image is absent.
      # shellcheck disable=SC2086
      docker compose --project-name "$PROJECT_NAME" --profile demo $COMPOSE_FILES \
        up --detach --wait
    fi
    echo "CrewScope Team Beta: http://127.0.0.1:$CREWSCOPE_WEB_PORT"
    echo "Open registration: http://127.0.0.1:$CREWSCOPE_WEB_PORT/register"
    echo "Operator login: http://127.0.0.1:$CREWSCOPE_WEB_PORT/login"
    echo "Operator username: crewscope-monitor"
    echo "Operator password file: $SECRETS_ROOT/bootstrap_password"
    echo "Prometheus machine user (not a Web login): crewscope-prometheus"
    ;;
  down)
    export_deployment_environment
    cd "$REPOSITORY_ROOT"
    # Persistent volumes and Secret files are deliberately retained across restarts.
    # shellcheck disable=SC2086
    docker compose --project-name "$PROJECT_NAME" --profile demo $COMPOSE_FILES \
      down --remove-orphans
    ;;
  reset)
    export_deployment_environment
    cd "$REPOSITORY_ROOT"
    # This removes only volumes owned by the explicitly named Compose project. Runtime Secret
    # files remain available for the next isolated run and are never printed by this script.
    # shellcheck disable=SC2086
    docker compose --project-name "$PROJECT_NAME" --profile demo $COMPOSE_FILES \
      down --remove-orphans --volumes
    ;;
  status)
    export_deployment_environment
    cd "$REPOSITORY_ROOT"
    # shellcheck disable=SC2086
    docker compose --project-name "$PROJECT_NAME" --profile demo $COMPOSE_FILES ps
    ;;
  logs)
    export_deployment_environment
    cd "$REPOSITORY_ROOT"
    # shellcheck disable=SC2086
    docker compose --project-name "$PROJECT_NAME" --profile demo $COMPOSE_FILES \
      logs --follow --tail 200
    ;;
  set-registration-mode)
    mode="${2:-}"
    case "$mode" in
      OPEN|INVITE_ONLY|DISABLED) ;;
      *)
        echo "Registration mode must be OPEN, INVITE_ONLY or DISABLED" >&2
        exit 2
        ;;
    esac
    export CREWSCOPE_REGISTRATION_MODE="$mode"
    export_deployment_environment
    cd "$REPOSITORY_ROOT"
    # Recreate only the API process. PostgreSQL, Redis and browser Session state remain durable,
    # which lets the release gate prove identity continuity while changing the registration policy.
    # shellcheck disable=SC2086
    docker compose --project-name "$PROJECT_NAME" --profile demo $COMPOSE_FILES \
      up --detach --no-deps --force-recreate --wait api
    echo "CrewScope registration mode: $mode"
    ;;
  *)
    usage
    exit 2
    ;;
esac
