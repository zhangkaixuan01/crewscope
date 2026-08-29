#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
lane="${1:-local-preflight}"
case "$lane" in
  local-preflight|release-candidate) ;;
  *) echo "Usage: $0 [local-preflight|release-candidate]" >&2; exit 2 ;;
esac

cd "$repository_root"
for command in docker git node pnpm; do
  command -v "$command" >/dev/null || {
    echo "$command is required for the M7 release gate." >&2
    exit 1
  }
done
node -e "const major = Number(process.versions.node.split('.')[0]); if (major < 24) process.exit(1)" || {
  echo "Node.js 24 or newer is required for the M7 release gate." >&2
  exit 1
}
docker info >/dev/null

cd "$repository_root/crewscope-web"
[[ "$(pnpm --version)" == 11.9.0 ]] || {
  echo "M7 release gate requires the repository-pinned pnpm 11.9.0." >&2
  exit 1
}
pnpm install --frozen-lockfile
cd "$repository_root"

# M6 is still the product baseline. Its gate owns the frozen coding/reviewer benchmarks, load,
# recovery, Lark release evidence and complete M0..M6 regression before M7 identity hardening runs.
node scripts/check-m7-release-contract.mjs
./scripts/m6-release-gate.sh "$lane"

./scripts/m7-q01-security-gate.sh
./scripts/m7-q02-convergence-gate.sh
./scripts/m7-q03-two-user-e2e-gate.sh
./scripts/m7-q04-registration-profile-gate.sh

node scripts/check-test-report-zero-skips.mjs
node scripts/check-doc-links.mjs
node scripts/check-team-beta-deployment.mjs
node scripts/check-team-beta-recovery.mjs
node scripts/check-web-sensitive-fields.mjs
git diff HEAD --check

docker build --file deploy/team-beta/backend.Dockerfile --tag crewscope-backend:m7-q04 .
docker build --file deploy/team-beta/web.Dockerfile --tag crewscope-web:m7-q04 .

cd "$repository_root/crewscope-web"
pnpm test:coverage
pnpm build
pnpm story:build
pnpm audit --prod --audit-level=high --registry=https://registry.npmjs.org
cd "$repository_root"

if [[ "$lane" == local-preflight ]]; then
  echo "M7-Q04 local preflight passed; Canonical recovery/Lark evidence and authoritative CI vulnerability scans remain required."
else
  echo "M7-Q04 release-candidate local gate passed; authoritative GitHub Actions OSV/Trivy scans remain required."
fi
