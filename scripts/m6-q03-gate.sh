#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
lane="${1:-fixture}"
case "$lane" in
  fixture|nightly|release-candidate) ;;
  *) echo "Usage: $0 [fixture|nightly|release-candidate]" >&2; exit 2 ;;
esac

cd "$repository_root"
for command in docker node pnpm git; do
  command -v "$command" >/dev/null || {
    echo "$command is required for the M6-Q03 gate." >&2
    exit 1
  }
done
node -e "const major = Number(process.versions.node.split('.')[0]); if (major < 24) process.exit(1)" || {
  echo "Node.js 24 or newer is required for the M6-Q03 gate." >&2
  exit 1
}
docker info >/dev/null

protocol_evidence="${CREWSCOPE_M6_Q03_PROTOCOL_EVIDENCE:-$repository_root/crewscope-infrastructure/target/m6-q03-load-evidence.json}"
production_evidence="${CREWSCOPE_M6_Q03_PRODUCTION_EVIDENCE:-$repository_root/crewscope-infrastructure/target/m6-q03-production-load-evidence.json}"
[[ "$protocol_evidence" = /* && "$production_evidence" = /* ]] || {
  echo "M6-Q03 evidence paths must be absolute." >&2
  exit 2
}
production_load_lane='fixture'
expected_production_load_lane='FIXTURE'
if [[ "$lane" != fixture ]]; then
  production_load_lane='canonical'
  expected_production_load_lane='CANONICAL'
  : "${CREWSCOPE_M6_Q03_SOURCE_OPERATOR_ENV:?source operator environment is required}"
  : "${CREWSCOPE_M6_Q03_TARGET_OPERATOR_ENV:?target operator environment is required}"
  [[ "$CREWSCOPE_M6_Q03_SOURCE_OPERATOR_ENV" = /* ]] || {
    echo "source operator environment path must be absolute" >&2
    exit 2
  }
  [[ "$CREWSCOPE_M6_Q03_TARGET_OPERATOR_ENV" = /* ]] || {
    echo "target operator environment path must be absolute" >&2
    exit 2
  }
  [[ "$(node -p "process.versions.node.split('.')[0]")" == 24 ]] || {
    echo "Canonical M6-Q03 requires Node.js 24." >&2
    exit 2
  }
  [[ "$(pnpm --version)" == 11.9.0 ]] || {
    echo "Canonical M6-Q03 requires pnpm 11.9.0." >&2
    exit 2
  }
  deploy/team-beta/operations/prepare-secret-permissions.sh \
    "$CREWSCOPE_M6_Q03_SOURCE_OPERATOR_ENV"
  deploy/team-beta/operations/prepare-secret-permissions.sh \
    "$CREWSCOPE_M6_Q03_TARGET_OPERATOR_ENV"
fi
node scripts/check-team-beta-deployment.mjs
node scripts/check-team-beta-recovery.mjs

# Keep the production latency window in its own JVM before the fault/recovery corpus. Fixture uses
# the Canonical request cadence with a shorter sample-bounded window; prior Testcontainers cleanup
# or fault injection must not become part of the measured queue/projector latency.
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-server -am \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dm6.q03.production.evidence="$production_evidence" \
  -Dm6.q03.production.lane="$production_load_lane" \
  -Dtest='ProductionQueueProjectionLoadM6Q03IntegrationTest' \
  test

# The remaining Java lane uses real PostgreSQL/Redis Testcontainers, real local Git and loopback
# Providers. It covers protocol load, process replacement, snapshot recovery and Worktree rollback.
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-server -am \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dm6.q03.evidence="$protocol_evidence" \
  -Dm6.q03.lane=fixture \
  -Dtest='TeamBetaFixedLoadM6Q03IntegrationTest,TeamBetaReleaseProtocolM6S05Test,DurableTaskWorkerStartupReconcilerM3Q02Test,AgentStateSnapshotM3S03IntegrationTest,RedisAgentStateM2I05IntegrationTest,GenerationAwareProjectionRuntimeM6E01IntegrationTest,ProjectionGenerationM6S01IntegrationTest,WorktreeProvisionerM4I03IntegrationTest,CodingWorkspaceStartupReconcilerM4I10Test,GitHubDeliveryM5S04IntegrationTest,LarkNotificationProviderM6I06IntegrationTest,NotificationWorkerM6I03Test,TeamBetaDeploymentGuardM6I09Test,TeamBetaBootstrapSeederM6I09IntegrationTest' \
  test
CREWSCOPE_M6_Q03_EXPECTED_PRODUCTION_LOAD_LANE="$expected_production_load_lane" \
  node scripts/check-m6-q03-evidence.mjs "$protocol_evidence" "$production_evidence"

# The Fixture MVP path spans Conversation, Task, Coding, Review, GitHub Delivery and every M6 team
# workspace. Playwright starts a fresh Vite server and route fixture for a repeatable clean run.
cd "$repository_root/crewscope-web"
pnpm install --frozen-lockfile
pnpm build
pnpm exec playwright test
cd "$repository_root"

restore_evidence=''
if [[ "$lane" != fixture ]]; then
  gate_temporary="$(mktemp -d)"
  trap 'rm -rf -- "$gate_temporary"' EXIT
  backup_output="$gate_temporary/backup.out"
  deploy/team-beta/operations/backup.sh \
    "$CREWSCOPE_M6_Q03_SOURCE_OPERATOR_ENV" release >"$backup_output"
  bundle="$(sed -n 's/^bundle=//p' "$backup_output")"
  [[ "$bundle" = /* && -f "$bundle" ]] || {
    echo "backup did not publish an absolute Bundle coordinate" >&2
    exit 2
  }
  restore_output="$gate_temporary/restore.out"
  deploy/team-beta/operations/restore.sh \
    "$CREWSCOPE_M6_Q03_TARGET_OPERATOR_ENV" "$bundle" --enable-traffic >"$restore_output"
  restore_evidence="$(sed -n 's/^evidence=//p' "$restore_output")"
  [[ "$restore_evidence" = /* && -f "$restore_evidence" ]] || {
    echo "restore did not publish an absolute Evidence coordinate" >&2
    exit 2
  }
  CREWSCOPE_M6_Q03_EXPECTED_PRODUCTION_LOAD_LANE="$expected_production_load_lane" \
    node scripts/check-m6-q03-evidence.mjs \
      "$protocol_evidence" "$production_evidence" "$restore_evidence"
fi

if [[ "$lane" == release-candidate ]]; then
  node scripts/m6-q03-real-lark-smoke.mjs
  CREWSCOPE_M6_Q03_EXPECTED_PRODUCTION_LOAD_LANE="$expected_production_load_lane" \
    node scripts/check-m6-q03-evidence.mjs \
      "$protocol_evidence" "$production_evidence" \
      "$restore_evidence" "$CREWSCOPE_M6_Q03_LARK_EVIDENCE"
fi

echo "M6-Q03 $lane gate passed."
