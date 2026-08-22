#!/usr/bin/env bash
set -euo pipefail

REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SANDBOX_IMAGE="maven@sha256:29a1658b1f3078e07c2b17f7b519b45eb47f65d9628e887eac45a8c5c8f939d4"

cd "$REPOSITORY_ROOT"

command -v docker >/dev/null || {
  echo "Docker is required for the M4-Q02 fault gate." >&2
  exit 1
}
command -v node >/dev/null || {
  echo "Node.js is required for the M4-Q02 fault gate." >&2
  exit 1
}
command -v pnpm >/dev/null || {
  echo "pnpm is required for the M4-Q02 fault gate." >&2
  exit 1
}
node -e "const major = Number(process.versions.node.split('.')[0]); if (major < 24) process.exit(1)" || {
  echo "Node.js 24 or newer is required for the M4-Q02 fault gate." >&2
  exit 1
}
docker info >/dev/null
docker image inspect "$SANDBOX_IMAGE" >/dev/null || {
  echo "Required sandbox image is missing: $SANDBOX_IMAGE" >&2
  echo "Run: docker pull $SANDBOX_IMAGE" >&2
  exit 1
}

# The list is the versioned Coding recovery corpus. It combines deterministic process-exit,
# filesystem, Docker, event, artifact and control-replay faults at their real ownership boundary.
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-server -am \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest='DurableTaskWorkerStartupReconcilerM3Q02Test,MemberTaskCommandServiceM3A04Test,CodingSpecialistStepRuntimeM4I12Test,DurableCodingSpecialistExecutionStoreM4I12Test,CodingWorkspaceRecoveryMarkerM4I10Test,CodingWorkspaceStartupReconcilerM4I10Test,DurableCodingWorkspaceExecutionLifecycleM4A03Test,WorktreeProvisionerM4I03IntegrationTest,TaskExecutionSandboxFactoryM4I04DockerIntegrationTest,BuildProfileCommandRunnerM4I07Test,WorkspaceDiffEventStoreM4I08Test,WorkspaceDiffWatcherM4I08Test,WorkspaceDiffFinalizerM4I08Test,FilesystemArtifactStoreIntegrationTest,TestEvidencePublisherM4A03Test' \
  test

cd "$REPOSITORY_ROOT/crewscope-web"
pnpm exec vitest run \
  src/domains/task/store.spec.ts \
  src/domains/coding/diff.spec.ts \
  src/domains/coding/store.spec.ts \
  src/components/domain/CodingProgressControl.spec.ts
