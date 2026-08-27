#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

cd "$repository_root"

command -v docker >/dev/null || {
  echo "Docker is required for the M6-Q02 fault gate." >&2
  exit 1
}
command -v node >/dev/null || {
  echo "Node.js is required for the M6-Q02 fault gate." >&2
  exit 1
}
command -v pnpm >/dev/null || {
  echo "pnpm is required for the M6-Q02 fault gate." >&2
  exit 1
}
node -e "const major = Number(process.versions.node.split('.')[0]); if (major < 24) process.exit(1)" || {
  echo "Node.js 24 or newer is required for the M6-Q02 fault gate." >&2
  exit 1
}
docker info >/dev/null

# FI-001..FI-121 freeze the denominator. The behavior corpus injects faults at each real owner:
# PostgreSQL/Testcontainers, Redis, local Git, AgentScope controlled models and loopback providers.
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-server -am \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest='M6FixedFaultRecoveryMatrixM6Q02Test,OutboxPublisherIntegrationTest,CheckpointedProjectionRunnerTest,ProjectionGenerationM6S01IntegrationTest,GenerationAwareProjectionRuntimeM6E01IntegrationTest,JdbcProjectionSupervisorStoreM6I02IntegrationTest,TeamActivityRealtimeStreamM6E05Test,RedisAgentStateM2I05IntegrationTest,AgentStateSnapshotM3S03IntegrationTest,DurableTaskWorkerStartupReconcilerM3Q02Test,TaskWorkerExecutionLoopM3I09Test,WorktreeProvisionerM4I03IntegrationTest,CodingWorkspaceStartupReconcilerM4I10Test,AgentScopeNativeRuntimeIntegrationTest,ResolvedAgentScopeModelFactoryM5I05Test,DynamicModelProviderIsolationM5I03IntegrationTest,ActionWorkerM5I11Test,ActionReconciliationWorkerM5I12Test,GitHubPushProtocolM5I09IntegrationTest,GitHubDraftPullRequestProtocolM5I10IntegrationTest,GitHubPullRequestWebhookAdapterM5I10Test,GitHubQueryOnlyProtocolM5I12Test,LarkConnectorM6I04IntegrationTest,LarkNotificationProviderM6I06IntegrationTest,NotificationWorkerM6I03Test,JdbcNotificationPlanRepositoryM6I01IntegrationTest,InboxEventProjectorM6E03IntegrationTest,JdbcOperationsRecoveryRepositoryM6I02IntegrationTest,JdbcActionWorkerPersistenceM5I11IntegrationTest' \
  test

cd "$repository_root/crewscope-web"
pnpm exec vitest run \
  src/domains/realtime/ThreeStreamRecoveryM6S02.spec.ts \
  src/domains/realtime/cursorStore.spec.ts \
  src/domains/teamops/activityRealtimeStore.spec.ts \
  src/domains/teamops/store.spec.ts \
  src/domains/task/store.spec.ts \
  src/domains/coding/store.spec.ts \
  src/domains/model/store.spec.ts \
  src/domains/delivery/store.spec.ts

echo "M6-Q02 fault gate passed (121/121 fixed faults converged)."
