#!/usr/bin/env sh
set -eu
umask 077

# One generated env file and one four-service Compose project, on laptops and servers alike.
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPOSITORY_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)
PROJECT_NAME="${CREWSCOPE_QUICKSTART_PROJECT_NAME:-${CREWSCOPE_DEMO_PROJECT_NAME:-crewscope-team-beta}}"
RUNTIME_ROOT="${CREWSCOPE_QUICKSTART_RUNTIME_ROOT:-${CREWSCOPE_DEMO_RUNTIME_ROOT:-$SCRIPT_DIR/.runtime}}"
ENV_FILE="$RUNTIME_ROOT/.env"
COMPOSE_FILE="$SCRIPT_DIR/compose.yaml"

fail() { echo "$*" >&2; exit 2; }
. "$SCRIPT_DIR/paths.sh"
usage() {
  echo "Usage: $0 init|up|build|down|reset|status|logs|config|compose [args]|set-registration-mode OPEN|INVITE_ONLY|DISABLED"
}

case "$PROJECT_NAME" in
  ''|[!a-z0-9]*|*[!a-z0-9_-]*) fail "Project name must start with a lowercase letter or number and contain only lowercase letters, numbers, '_' or '-'." ;;
esac
case "$RUNTIME_ROOT" in
  /*) ;;
  *) fail "CREWSCOPE_QUICKSTART_RUNTIME_ROOT must be an absolute path." ;;
esac

# Read data, never execute a user-editable env file as shell code.
env_value() { awk -v key="$1" 'index($0, key "=") == 1 { print substr($0, length(key) + 2); exit }' "$ENV_FILE"; }
has_env_key() { awk -v key="$1" 'index($0, key "=") == 1 { found = 1 } END { exit !found }' "$ENV_FILE"; }

prepare_env() {
  assert_dedicated_directory "$RUNTIME_ROOT" "$REPOSITORY_ROOT" "${HOME:-}"
  [ ! -L "$ENV_FILE" ] || fail "Configuration must not be a symlink: $ENV_FILE"
  [ ! -L "$RUNTIME_ROOT/bootstrap_password" ] || fail "Bootstrap password file must not be a symlink."
  mkdir -p "$RUNTIME_ROOT"
  chmod 700 "$RUNTIME_ROOT"
  if [ ! -f "$ENV_FILE" ]; then
    command -v openssl >/dev/null || fail "OpenSSL is required for first-time initialization."
    # Publish only a complete file, so an interrupted first start can be retried safely.
    env_temp=$(mktemp "$RUNTIME_ROOT/.env.XXXXXX")
    trap 'test -z "${env_temp:-}" || rm -f "$env_temp"' EXIT HUP INT TERM
    database_password=$(openssl rand -hex 24)
    bootstrap_password=$(openssl rand -hex 24)
    monitoring_password=$(openssl rand -hex 24)
    credential_key=$(openssl rand -base64 32)
    activity_key=$(openssl rand -base64 32)
    invitation_key=$(openssl rand -base64 32)
    task_key=$(openssl rand -base64 32)
    login_key=$(openssl rand -base64 32)
    diff_secret=$(openssl rand -hex 32)
    {
      printf 'CREWSCOPE_DB_PASSWORD=%s\n' "$database_password"
      printf 'CREWSCOPE_BOOTSTRAP_PASSWORD=%s\n' "$bootstrap_password"
      printf 'CREWSCOPE_MONITORING_PASSWORD=%s\n' "$monitoring_password"
      printf 'CREWSCOPE_CREDENTIAL_KEYS=v1=%s\n' "$credential_key"
      printf 'CREWSCOPE_TEAM_ACTIVITY_CURSOR_KEY_V1=%s\n' "$activity_key"
      printf 'CREWSCOPE_INVITATION_TOKEN_HMAC_KEY=%s\n' "$invitation_key"
      printf 'CREWSCOPE_TASK_TOKEN_KEY_V1=%s\n' "$task_key"
      printf 'CREWSCOPE_LOGIN_DEFENSE_HMAC_KEY=%s\n' "$login_key"
      printf 'CREWSCOPE_CODING_DIFF_CURSOR_SECRET=%s\n' "$diff_secret"
      printf 'CREWSCOPE_DEMO_ORGANIZATION_ID=0198a475-0831-7000-8000-000000000001\n'
      printf 'CREWSCOPE_DEMO_RUNTIME_PRINCIPAL_ID=0198a475-0831-7000-8000-000000000002\n'
      printf 'CREWSCOPE_DEMO_ORGANIZATION_NAME=%s\n' "${CREWSCOPE_DEMO_ORGANIZATION_NAME:-CrewScope Team Beta}"
      printf 'CREWSCOPE_REGISTRATION_MODE=%s\n' "${CREWSCOPE_REGISTRATION_MODE:-OPEN}"
      printf 'CREWSCOPE_WEB_PORT=%s\n' "${CREWSCOPE_WEB_PORT:-8080}"
      printf 'CREWSCOPE_ENVIRONMENT=%s\n' "$PROJECT_NAME"
      printf 'CREWSCOPE_EXECUTION_ROOT=%s/execution\n' "$RUNTIME_ROOT"
    } >"$env_temp"
    # Never replace keys if two first starts race to initialize the same installation.
    ln "$env_temp" "$ENV_FILE" || fail "Configuration was initialized concurrently; retry the command."
    rm -f "$env_temp"
    env_temp=
    trap - EXIT HUP INT TERM
  fi
  for key in CREWSCOPE_DB_PASSWORD CREWSCOPE_BOOTSTRAP_PASSWORD CREWSCOPE_CREDENTIAL_KEYS CREWSCOPE_TEAM_ACTIVITY_CURSOR_KEY_V1 CREWSCOPE_INVITATION_TOKEN_HMAC_KEY CREWSCOPE_CODING_DIFF_CURSOR_SECRET; do
    [ -n "$(env_value "$key")" ] || fail "Incomplete $ENV_FILE: missing $key. Restore the original env/keys if data exists; for an unused installation, move this incomplete file aside and run init again."
  done
  # Add new non-destructive settings to the earlier four-service env without rotating stored keys.
  if ! has_env_key CREWSCOPE_TASK_TOKEN_KEY_V1; then
    task_key=$(openssl rand -base64 32)
    printf '\nCREWSCOPE_TASK_TOKEN_KEY_V1=%s\n' "$task_key" >>"$ENV_FILE"
  fi
  if ! has_env_key CREWSCOPE_LOGIN_DEFENSE_HMAC_KEY; then
    login_key=$(openssl rand -base64 32)
    printf '\nCREWSCOPE_LOGIN_DEFENSE_HMAC_KEY=%s\n' "$login_key" >>"$ENV_FILE"
  fi
  if ! has_env_key CREWSCOPE_EXECUTION_ROOT; then
    printf '\nCREWSCOPE_EXECUTION_ROOT=%s/execution\n' "$RUNTIME_ROOT" >>"$ENV_FILE"
  fi
  for key in CREWSCOPE_TASK_TOKEN_KEY_V1 CREWSCOPE_LOGIN_DEFENSE_HMAC_KEY CREWSCOPE_EXECUTION_ROOT; do
    [ -n "$(env_value "$key")" ] || fail "Incomplete $ENV_FILE: empty $key. Restore its original value; existing keys are never silently rotated."
  done
  chmod 600 "$ENV_FILE"
  env_value CREWSCOPE_BOOTSTRAP_PASSWORD >"$RUNTIME_ROOT/bootstrap_password"
  chmod 600 "$RUNTIME_ROOT/bootstrap_password"
}

compose() {
  CREWSCOPE_ENV_FILE="$ENV_FILE" \
  CREWSCOPE_DOCKER_GID="${CREWSCOPE_DOCKER_GID:-$(if [ -f "$RUNTIME_ROOT/docker-gid" ]; then tr -d '\n' <"$RUNTIME_ROOT/docker-gid"; else echo 0; fi)}" \
    docker compose --project-name "$PROJECT_NAME" --env-file "$ENV_FILE" --file "$COMPOSE_FILE" "$@"
}

prepare_execution() {
  execution_root="${CREWSCOPE_EXECUTION_ROOT:-$(env_value CREWSCOPE_EXECUTION_ROOT)}"
  assert_dedicated_directory "$execution_root" "$REPOSITORY_ROOT" "$RUNTIME_ROOT" "${HOME:-}"
  for directory in repositories worktrees worktree-locks github-mirrors; do
    [ ! -L "$execution_root/$directory" ] || fail "Execution subdirectory must not be a symlink: $directory"
  done
  docker_socket="${CREWSCOPE_DOCKER_SOCKET:-$(env_value CREWSCOPE_DOCKER_SOCKET)}"
  docker_socket="${docker_socket:-/var/run/docker.sock}"
  [ -S "$docker_socket" ] || fail "Docker socket missing: $docker_socket. Set CREWSCOPE_DOCKER_SOCKET to the local Docker Unix socket."
  mkdir -p "$execution_root"
  # Short-lived helper initializes only dedicated runtime directories; no recursive chown.
  docker run --rm --user 0 --entrypoint /bin/sh \
    --mount "type=bind,src=$execution_root,dst=/execution" \
    --mount "type=bind,src=$docker_socket,dst=/var/run/docker.sock" \
    crewscope-backend:local -ec '
      chown 10001:10001 /execution
      chmod 750 /execution
      for directory in repositories worktrees worktree-locks github-mirrors; do
        install -d -o 10001 -g 10001 "/execution/$directory"
      done
      stat -c %g /var/run/docker.sock
    ' >"$RUNTIME_ROOT/docker-gid"
  # Repairs root-owned mount roots produced by the previous Compose, preserving their contents.
  compose run --rm --no-deps --user 0 --entrypoint /bin/sh api -ec '
    for directory in agent-runtime template-agent-runtime task-agent-runtime coding-agent-runtime; do
      chown 10001:10001 "/var/crewscope/$directory"
    done
  '
}

action="${1:-}"
case "$action" in
  init|up|build|down|reset|status|logs|config|compose|set-registration-mode) ;;
  *) usage; exit 2 ;;
esac
if [ "$action" = set-registration-mode ]; then
  case "${2:-}" in OPEN|INVITE_ONLY|DISABLED) ;; *) fail "Registration mode must be OPEN, INVITE_ONLY or DISABLED." ;; esac
fi
prepare_env
case "$action" in
  init) echo "Configuration ready: $ENV_FILE" ;;
  build) compose build api web ;;
  up)
    docker info >/dev/null
    if ! docker image inspect crewscope-backend:local crewscope-web:local >/dev/null 2>&1; then
      compose build api web
    fi
    prepare_execution
    compose up --detach --wait
    web_port="${CREWSCOPE_WEB_PORT:-$(env_value CREWSCOPE_WEB_PORT)}"
    echo "CrewScope Team Beta: http://127.0.0.1:${web_port:-8080}"
    echo "Operator username: crewscope-monitor"
    echo "Operator password file: $RUNTIME_ROOT/bootstrap_password"
    ;;
  down) compose down --remove-orphans ;;
  reset)
    compose down --remove-orphans --volumes
    echo "Project volumes removed. Configuration and execution repositories/worktrees are retained in $RUNTIME_ROOT."
    ;;
  status) compose ps ;;
  logs) compose logs --follow --tail 200 ;;
  config) compose config --quiet; echo "Compose configuration is valid." ;;
  compose) shift; compose "$@" ;;
  set-registration-mode)
    mode="$2"
    mode_temp=$(mktemp "$RUNTIME_ROOT/.env.XXXXXX")
    awk -v mode="$mode" '
      BEGIN { replaced = 0 }
      /^CREWSCOPE_REGISTRATION_MODE=/ { print "CREWSCOPE_REGISTRATION_MODE=" mode; replaced = 1; next }
      { print }
      END { if (!replaced) print "CREWSCOPE_REGISTRATION_MODE=" mode }
    ' "$ENV_FILE" >"$mode_temp"
    mv "$mode_temp" "$ENV_FILE"
    # An inherited shell override must not undo the requested persisted registration mode.
    export CREWSCOPE_REGISTRATION_MODE="$mode"
    compose up --detach --no-deps --force-recreate --wait api
    echo "CrewScope registration mode: $mode"
    ;;
esac
