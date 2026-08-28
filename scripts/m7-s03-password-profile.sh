#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPOSITORY_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
PROFILE_NAME="${1:-development}"
CONCURRENCY="${2:-1,2,4}"
WARMUP="${M7_S03_WARMUP:-2}"
SAMPLES="${M7_S03_SAMPLES:-7}"
CLASSPATH_FILE="$REPOSITORY_ROOT/crewscope-server/target/m7-s03-classpath.txt"

cd "$REPOSITORY_ROOT"
./mvnw -q -pl crewscope-server -am -DskipTests test-compile
./mvnw -q -pl crewscope-server dependency:build-classpath \
  -Dmdep.includeScope=test \
  -Dmdep.outputFile=target/m7-s03-classpath.txt

if [[ ! -s "$CLASSPATH_FILE" ]]; then
  echo "M7-S03 classpath was not generated: $CLASSPATH_FILE" >&2
  exit 1
fi

exec java \
  -cp "$REPOSITORY_ROOT/crewscope-server/target/test-classes:$REPOSITORY_ROOT/crewscope-server/target/classes:$(<"$CLASSPATH_FILE")" \
  io.crewscope.server.security.PasswordSecurityProfileM7S03Test \
  "--profile=$PROFILE_NAME" \
  "--warmup=$WARMUP" \
  "--samples=$SAMPLES" \
  "--concurrency=$CONCURRENCY"
