#!/usr/bin/env bash
set -euo pipefail

REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SANDBOX_IMAGE="maven@sha256:29a1658b1f3078e07c2b17f7b519b45eb47f65d9628e887eac45a8c5c8f939d4"

cd "$REPOSITORY_ROOT"

command -v docker >/dev/null || {
  echo "Docker is required for the M4-Q01 security gate." >&2
  exit 1
}
command -v node >/dev/null || {
  echo "Node.js is required for the M4-Q01 security gate." >&2
  exit 1
}
command -v pnpm >/dev/null || {
  echo "pnpm is required for the M4-Q01 security gate." >&2
  exit 1
}
node -e "const major = Number(process.versions.node.split('.')[0]); if (major < 24) process.exit(1)" || {
  echo "Node.js 24 or newer is required for the M4-Q01 security gate." >&2
  exit 1
}
docker info >/dev/null
docker image inspect "$SANDBOX_IMAGE" >/dev/null || {
  echo "Required sandbox image is missing: $SANDBOX_IMAGE" >&2
  echo "Run: docker pull $SANDBOX_IMAGE" >&2
  exit 1
}

# This list is the versioned M4 attack corpus. New Coding execution surfaces must add their
# security regression class here before M4-Q04 can release the milestone.
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-server -am \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest='TaskTokenFixedAttackSetM3Q01Test,WorkspacePolicyTest,RepositoryBindingAccessPolicyM4A01Test,CodingArtifactAccessServiceM4A06Test,CodingArtifactAccessServiceM4Q01Test,AgentScopeCodingRuntimeM4I11IntegrationTest,GitCommandExecutorM4I01IntegrationTest,ManagedRepositoryResolverM4I02IntegrationTest,WorktreeProvisionerM4I03IntegrationTest,TaskExecutionSandboxFactoryM4I04DockerIntegrationTest,DockerContainerSnapshotM4Q01Test,RepositoryInspectionToolM4I05Test,CodingFilesystemToolM4I06Test,BuildProfileCommandRunnerM4I07Test,CodingArtifactServiceM4I09Test,RepositoryBindingControllerM4A01Test,CodingArtifactControllerM4A06Test,RuntimeOperationsControllerM4A07Test,StructuredLogSanitizerTest' \
  test

cd "$REPOSITORY_ROOT/crewscope-web"
pnpm exec vitest run \
  src/domains/coding/gateway.spec.ts \
  src/domains/coding/diff.spec.ts \
  src/domains/coding/store.spec.ts \
  src/components/domain/CodingDiffExplorer.spec.ts \
  src/components/domain/CodingEvidencePanel.spec.ts \
  src/pages/RepositorySettingsPage.spec.ts
