#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repository_root"

node_major="$(node --version | sed -E 's/^v([0-9]+).*/\1/')"
if [[ "$node_major" -lt 24 ]]; then
  echo "M5-Q03 requires Node.js 24 or newer." >&2
  exit 1
fi

node evaluation/m5/reviewer-q03/scripts/benchmark.mjs validate
node --test evaluation/m5/reviewer-q03/scripts/benchmark.test.mjs

./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-server -am \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest='AgentScopeDynamicModelM5S01IntegrationTest,DynamicModelProviderIsolationM5I03IntegrationTest,ResolvedAgentScopeModelFactoryM5I05Test,ReviewerSpecialistM5S03IntegrationTest,ReviewerStructuredOutputM5I06Test,ReviewerSpecialistRuntimeM5I06Test,ReviewerQualityBenchmarkM5Q03Test' \
  test

if [[ "${1:-}" != "--real" ]]; then
  echo "M5-Q03 protocol gate passed. Use --real for the credentialed DeepSeek quality track."
  exit 0
fi

if [[ -f "$repository_root/.env" ]]; then
  # The local file is user-owned configuration. Values are exported but never echoed or archived.
  set -a
  # shellcheck disable=SC1091
  source "$repository_root/.env"
  set +a
fi

: "${OPENAI_API_KEY:?OPENAI_API_KEY is required for the M5-Q03 real-model gate}"
: "${AGENTSCOPE_OPENAI_BASE_URL:?AGENTSCOPE_OPENAI_BASE_URL is required}"
: "${AGENTSCOPE_OPENAI_MODEL_NAME:?AGENTSCOPE_OPENAI_MODEL_NAME is required}"
evaluation_provider="${CREWSCOPE_M5_Q03_PROVIDER:-deepseek}"
run_id="m5-q03-${evaluation_provider}-${AGENTSCOPE_OPENAI_MODEL_NAME}-$(date -u +%Y%m%dT%H%M%SZ)"
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-agentscope -am \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=ReviewerQualityBenchmarkM5Q03Test \
  -Dcrewscope.m5.q03.real.enabled=true \
  -Dcrewscope.m5.q03.run-id="$run_id" \
  test

echo "M5-Q03 credentialed quality report: var/evaluation/m5-q03/results/$run_id/aggregate.json"
