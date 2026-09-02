#!/usr/bin/env sh

# Prepare Linux file-backed Compose Secrets for the fixed Team Beta runtime identities without
# exposing their values or making them world-readable.
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# shellcheck source=common.sh
. "$SCRIPT_DIR/common.sh"

load_operator_environment "${1:-}"
[ "$(id -u)" -eq 0 ] || fail "Secret permission preparation must run as root"

# Create every bind source before Compose starts. Docker-created missing directories are owned by
# root and would make the read-only API/Worker containers fail before model invocation or Git I/O.
for runtime_directory in artifacts github-mirrors repositories worktrees worktree-locks runtime; do
  mkdir -p "$CREWSCOPE_DATA_ROOT/$runtime_directory"
  chown -R 10001:10001 "$CREWSCOPE_DATA_ROOT/$runtime_directory"
done
# Keep the four AgentScope runtime roots explicit: a fresh host may not have any of these
# subdirectories yet, and Compose would otherwise create them as root after this script returns.
for runtime_directory in personal-agent template-agent task-agent coding-agent; do
  mkdir -p "$CREWSCOPE_DATA_ROOT/runtime/$runtime_directory"
  chown -R 10001:10001 "$CREWSCOPE_DATA_ROOT/runtime/$runtime_directory"
done
mkdir -p "$CREWSCOPE_DATA_ROOT/metrics"
chown root:root "$CREWSCOPE_DATA_ROOT/metrics"
chmod 0755 "$CREWSCOPE_DATA_ROOT/metrics"

chown 0:0 "$CREWSCOPE_SECRETS_ROOT"
chmod 0700 "$CREWSCOPE_SECRETS_ROOT"

for secret_name in database_password bootstrap_password monitoring_password credential_keys \
    activity_cursor_key diff_cursor_secret task_token_key redis_url login_defense_hmac_key \
    invitation_token_hmac_key; do
  secret_file="$CREWSCOPE_SECRETS_ROOT/$secret_name"
  require_regular_file "$secret_file" "Team Beta Secret"
  [ ! -L "$secret_file" ] || fail "Team Beta Secret must not be a symbolic link: $secret_name"
  chown 0:10001 "$secret_file"
  chmod 0440 "$secret_file"
done

redis_acl="$CREWSCOPE_SECRETS_ROOT/redis_acl"
require_regular_file "$redis_acl" "Redis ACL Secret"
[ ! -L "$redis_acl" ] || fail "Redis ACL Secret must not be a symbolic link"
chown 0:0 "$redis_acl"
chmod 0600 "$redis_acl"

[ ! -L "$CREWSCOPE_BACKUP_PASSPHRASE_FILE" ] \
  || fail "backup passphrase file must not be a symbolic link"
chown 0:0 "$CREWSCOPE_BACKUP_PASSPHRASE_FILE"
chmod 0600 "$CREWSCOPE_BACKUP_PASSPHRASE_FILE"

printf 'Team Beta Secret permissions prepared.\n'
