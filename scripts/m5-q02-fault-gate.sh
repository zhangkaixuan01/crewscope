#!/usr/bin/env bash
set -euo pipefail

REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

cd "$REPOSITORY_ROOT"

command -v docker >/dev/null || {
  echo "Docker is required for the M5-Q02 fault gate." >&2
  exit 1
}
command -v node >/dev/null || {
  echo "Node.js is required for the M5-Q02 fault gate." >&2
  exit 1
}
command -v pnpm >/dev/null || {
  echo "pnpm is required for the M5-Q02 fault gate." >&2
  exit 1
}
node -e "const major = Number(process.versions.node.split('.')[0]); if (major < 24) process.exit(1)" || {
  echo "Node.js 24 or newer is required for the M5-Q02 fault gate." >&2
  exit 1
}
docker info >/dev/null

# This versioned corpus exercises each fault at the boundary that owns recovery. It never calls
# a real model or GitHub account; loopback Provider fixtures and real PostgreSQL/Git are used.
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-server -am \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest='AgentScopeNativeRuntimeIntegrationTest,ResolvedAgentScopeModelFactoryM5I05Test,DynamicModelProviderIsolationM5I03IntegrationTest,ReviewerSpecialistRuntimeM5I06Test,ModelConnectionCredentialServiceTest,CachedModelConnectionAvailabilityVerifierTest,OpenAiCompatibleModelProviderHealthProbeTest,AgentExecutionConfigurationResolverTest,TaskAgentSelectionServiceM5A04Test,ReviewGateApplicationServiceM5A05Test,ReviewerExecutionApplicationServiceM5A05Test,ReviewFindingBatchRecorderM5I06Test,ActionDeliveryTest,ActionDeliveryApplicationServiceM5A07Test,ActionWorkerM5I11Test,ActionReconciliationWorkerM5I12Test,ActionManualResolutionServiceM5I12Test,GitHubProviderAdapterM5I08Test,GitHubPushProtocolM5I09IntegrationTest,GitHubDraftPullRequestProtocolM5I10IntegrationTest,GitHubPullRequestWebhookAdapterM5I10Test,GitHubQueryOnlyProtocolM5I12Test,JdbcReviewPersistenceM5I07IntegrationTest,JdbcActionWorkerPersistenceM5I11IntegrationTest' \
  test

cd "$REPOSITORY_ROOT/crewscope-web"
pnpm exec vitest run \
  src/domains/model/store.spec.ts \
  src/domains/agent/store.spec.ts \
  src/domains/review/store.spec.ts \
  src/domains/delivery/store.spec.ts \
  src/components/domain/ModelCredentialDialog.spec.ts \
  src/components/domain/DelegateToAgentDialog.spec.ts \
  src/components/domain/ReviewWorkbench.spec.ts \
  src/components/domain/ActionDeliveryWorkbench.spec.ts

echo "M5-Q02 fault gate passed."
