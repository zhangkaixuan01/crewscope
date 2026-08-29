#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

cd "$repository_root"

command -v docker >/dev/null || {
  echo "Docker is required for the M7-Q01 Redis Session and login-defense gate." >&2
  exit 1
}
docker info >/dev/null 2>&1 || {
  echo "Docker must be running for the M7-Q01 security gate." >&2
  exit 1
}
command -v node >/dev/null || {
  echo "Node.js is required for the M7-Q01 security gate." >&2
  exit 1
}
command -v pnpm >/dev/null || {
  echo "pnpm is required for the M7-Q01 security gate." >&2
  exit 1
}
node -e "const major = Number(process.versions.node.split('.')[0]); if (major < 24) process.exit(1)" || {
  echo "Node.js 24 or newer is required for the M7-Q01 security gate." >&2
  exit 1
}

# PW-01..PW-16, BF-01..BF-12, EN-01..EN-12, CS-01..CS-10, OR-01..OR-16,
# SS-01..SS-14, CK-01..CK-08, LK-01..LK-16 and RD-01..RD-24 form the stable 128-case attack
# denominator. The remaining tests prove that the fixed defenses preserve legitimate registration,
# login, Session rotation/renewal/logout and account operations against real Redis state.
./mvnw --batch-mode --no-transfer-progress \
  -pl crewscope-server -am \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest='M7AuthenticationPolicyFixedAttackSetM7Q01Test,M7AuthenticationHttpFixedAttackSetM7Q01Test,M7SessionFixedAttackSetM7Q01Test,M7CookieFixedAttackSetM7Q01Test,M7IdentityDisclosureFixedAttackSetM7Q01Test,AuthenticationPolicyM7D03Test,M7I03LocalCredentialSecurityIntegrationTest,AuthenticationControllerM7A02Test,RegistrationControllerM7A01Test,CurrentAccountControllerM7A03Test,M7ApiContractM7A07Test,AuthenticationRouteSecurityM7A06Test,LocalSessionSecurityM7S01IntegrationTest,BrowserSessionConfigurationM7I02Test,BrowserSessionLifecycleM7I02IntegrationTest,LoginDefenseBoundaryM7I04Test,RedisLoginDefenseM7I04IntegrationTest,StructuredLogSanitizerTest,TeamBetaDeploymentGuardM6I09Test' \
  test

cd "$repository_root/crewscope-web"
pnpm exec vitest run \
  src/domains/identity/M7OpenRedirectFixedAttackSetM7Q01.spec.ts \
  src/domains/identity/route.spec.ts \
  src/domains/identity/gateway.spec.ts \
  src/domains/identity/store.spec.ts \
  src/api/client.spec.ts
pnpm run check:sensitive

echo "M7-Q01 security gate passed (128/128 fixed attacks blocked)."
