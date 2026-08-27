#!/usr/bin/env sh

# Prepare Linux file-backed Compose Secrets for the fixed Team Beta runtime identities without
# exposing their values or making them world-readable.
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# shellcheck source=common.sh
. "$SCRIPT_DIR/common.sh"

load_operator_environment "${1:-}"
[ "$(id -u)" -eq 0 ] || fail "Secret permission preparation must run as root"

chown 0:0 "$CREWSCOPE_SECRETS_ROOT"
chmod 0700 "$CREWSCOPE_SECRETS_ROOT"

for secret_name in database_password bootstrap_password credential_keys activity_cursor_key \
    diff_cursor_secret task_token_key redis_url; do
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
