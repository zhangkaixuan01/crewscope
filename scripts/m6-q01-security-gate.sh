#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

cd "$repository_root"

command -v node >/dev/null || {
  echo "Node.js is required for the M6-Q01 security gate." >&2
  exit 1
}
command -v pnpm >/dev/null || {
  echo "pnpm is required for the M6-Q01 security gate." >&2
  exit 1
}
node -e "const major = Number(process.versions.node.split('.')[0]); if (major < 24) process.exit(1)" || {
  echo "Node.js 24 or newer is required for the M6-Q01 security gate." >&2
  exit 1
}

# CU-01..CU-36, LK-01..LK-50 and WR-01..WR-24 form the stable 110-case attack
# denominator. Behavioral regressions below prove that successful parsing still cannot bypass
# current authorization, read-only tools, fixed templates or strongly confirmed operations.
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-server -am \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest='M6CursorFixedAttackSetM6Q01Test,M6PublicProjectionFixedAttackSetM6Q01Test,TeamActivityCursorCodecM6E05Test,InboxCursorCodecM6A02Test,AuditCursorCodecM6A03Test,CorrelationCursorCodecM6A07Test,LarkAdministrationCursorM6A04Test,ActivityApplicationServiceM6A01Test,InboxApplicationServiceM6A02Test,AuditQueryApplicationServiceM6A03Test,LarkAdministrationConcurrencyM6A04Test,FixedNotificationTemplateRendererM6I06Test,NotificationWorkerM6I03Test,DefaultTeamObserverServiceM6D05Test,TeamObserverReadServiceM6I07Test,TeamObserverInvocationServiceM6A05Test,TeamObserverRuntimeM6I07Test,AgentScopeTeamObserverExecutionAdapterM6A05Test,ProjectionAdministrationServiceM6D07Test,OperationsRecoveryServiceM6E07Test,OperationsControllerM6A06Test,TeamObserverControllerM6A05Test' \
  test

cd "$repository_root/crewscope-web"
pnpm exec vitest run \
  src/domains/teamobserver/M6FixedAttackSetM6Q01.spec.ts \
  src/domains/teamobserver/gateway.spec.ts \
  src/domains/teamobserver/store.spec.ts \
  src/domains/teamops/gateway.spec.ts \
  src/domains/teamops/store.spec.ts \
  src/domains/teamops/activityRealtimeStore.spec.ts \
  src/domains/realtime/cursorStore.spec.ts \
  src/domains/realtime/ThreeStreamRecoveryM6S02.spec.ts
pnpm run check:sensitive

echo "M6-Q01 security gate passed (110/110 fixed attacks blocked)."
