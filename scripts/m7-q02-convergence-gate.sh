#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

cd "$repository_root"

command -v docker >/dev/null || {
  echo "Docker is required for the M7-Q02 PostgreSQL and Redis convergence gate." >&2
  exit 1
}
docker info >/dev/null 2>&1 || {
  echo "Docker must be running for the M7-Q02 convergence gate." >&2
  exit 1
}

# CF-001..CF-072 freeze the registration, Organization Binding, invitation, Membership,
# migration, Redis, transaction, Operator and process-recovery denominator. Real PostgreSQL 17
# and Redis 7.4 tests own the behavior evidence; Testcontainers skips therefore fail the gate.
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-server -am \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest='M7FixedIdentityConvergenceMatrixM7Q02Test,M7IdentityInvitationTransactionConvergenceM7Q02IntegrationTest,M7I01IdentityPersistenceIntegrationTest,JdbcTeamInvitationRepositoryM7I06IntegrationTest,V31LocalUserAccountIdentityMigrationIntegrationTest,V32TeamInvitationMigrationIntegrationTest,BootstrapOperatorProvisioningM7I07IntegrationTest,LocalAccountRegistrationServiceM7A01Test,TeamInvitationApplicationServiceM7A05Test,RegistrationControllerM7A01Test,BrowserSessionLifecycleM7I02IntegrationTest,RedisLoginDefenseM7I04IntegrationTest' \
  test

echo "M7-Q02 convergence gate passed (72/72 fixed failures converged)."
