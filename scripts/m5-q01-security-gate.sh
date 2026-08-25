#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

cd "$repository_root"

command -v node >/dev/null || {
  echo "Node.js is required for the M5-Q01 security gate." >&2
  exit 1
}
command -v pnpm >/dev/null || {
  echo "pnpm is required for the M5-Q01 security gate." >&2
  exit 1
}
node -e "const major = Number(process.versions.node.split('.')[0]); if (major < 24) process.exit(1)" || {
  echo "Node.js 24 or newer is required for the M5-Q01 security gate." >&2
  exit 1
}

# Every class below owns at least one coordinate in the frozen 84-case attack matrix. Keep the
# public-projection probe in the same gate so authorization and disclosure cannot drift apart.
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-server -am \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest='AgentConfigurationVersionTest,AgentManagementApplicationServiceM5A02Test,AgentConfigurationApplicationServiceM5A03Test,AgentExecutionConfigurationResolverTest,ModelConnectionApplicationServiceM5A01Test,ModelConnectionCredentialBoundaryTest,AgentTemplateRuntimeRegistryM5I05Test,ReviewFindingTest,ReviewDecisionTest,ReviewRepositoryTenantBoundaryTest,ReviewFindingBatchRecorderM5I06Test,ReviewGateApplicationServiceM5A05Test,ActionBundleTest,ActionDeliveryTest,ActionDeliveryApplicationServiceM5A07Test,CodingArtifactAccessServiceM4Q01Test,GitHubProviderAdapterM5I08Test,GitHubPullRequestWebhookAdapterM5I10Test,GitAskPassSessionM5I09Test,M5PublicProjectionFixedAttackSetM5Q01Test,AgentManagementControllerM5A02Test,AgentConfigurationControllerM5A03Test,ModelManagementControllerM5A01Test,ReviewControllerM5A05Test,GitHubConnectionControllerM5A06Test,ActionDeliveryControllerM5A07Test' \
  test

cd "$repository_root/crewscope-web"
pnpm run check:sensitive

echo "M5-Q01 security gate passed."
