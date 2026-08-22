#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repository_root"

node_major="$(node --version | sed -E 's/^v([0-9]+).*/\1/')"
if [[ "$node_major" -lt 24 ]]; then
  echo "M4-Q03 requires Node.js 24 or newer." >&2
  exit 1
fi

docker info >/dev/null
sandbox_image="maven@sha256:29a1658b1f3078e07c2b17f7b519b45eb47f65d9628e887eac45a8c5c8f939d4"
docker image inspect "$sandbox_image" >/dev/null

node evaluation/m4/coding-v1/scripts/evaluate.mjs validate
node evaluation/m4/coding-q03/scripts/benchmark.mjs validate
node --test evaluation/m4/coding-q03/scripts/benchmark.test.mjs
node --test evaluation/m4/coding-q03/scripts/benchmark.integration.test.mjs

./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-server -am \
  -Dtest=AgentScopeModelConfigurationM4Q03Test \
  -Dsurefire.failIfNoSpecifiedTests=false \
  test

echo "M4-Q03 protocol gate passed. Real-model aggregate remains a separate credentialed gate."
