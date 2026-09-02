#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
lane="${1:-local-precheck}"
case "$lane" in
  contracts-only|local-precheck) ;;
  *) echo "Usage: $0 [contracts-only|local-precheck]" >&2; exit 2 ;;
esac

cd "$repository_root"
for command in docker git java node pnpm; do
  command -v "$command" >/dev/null || {
    echo "$command is required for the M8-Q02 local gate." >&2
    exit 1
  }
done
node -e "const major = Number(process.versions.node.split('.')[0]); if (major < 24) process.exit(1)" || {
  echo "Node.js 24 or newer is required for the M8-Q02 local gate." >&2
  exit 1
}
[[ "$(pnpm --version)" == 11.9.0 ]] || {
  echo "M8-Q02 requires the repository-pinned pnpm 11.9.0." >&2
  exit 1
}
docker info >/dev/null

for check_script in \
  scripts/check-module-boundaries.mjs \
  scripts/check-dependency-contract.mjs \
  scripts/check-config-contract.mjs \
  scripts/check-release-contract.mjs \
  scripts/check-team-beta-deployment.mjs \
  scripts/check-team-beta-recovery.mjs \
  scripts/check-web-quality.mjs \
  scripts/check-web-sensitive-fields.mjs \
  scripts/check-doc-links.mjs
do
  node "$check_script"
done
git diff HEAD --check
untracked_whitespace_failed=0
while IFS= read -r -d '' untracked_file; do
  untracked_check="$(git diff --no-index --check /dev/null "$untracked_file" 2>&1 || true)"
  if [[ -n "$untracked_check" ]]; then
    printf '%s\n' "$untracked_check" >&2
    untracked_whitespace_failed=1
  fi
done < <(git ls-files --others --exclude-standard -z)
[[ "$untracked_whitespace_failed" -eq 0 ]] || exit 1

if [[ "$lane" == contracts-only ]]; then
  echo "M8-Q02 local contracts passed."
  exit 0
fi

cd "$repository_root/crewscope-web"
pnpm install --frozen-lockfile
cd "$repository_root"

./mvnw --batch-mode --no-transfer-progress clean verify
./scripts/m7-q01-security-gate.sh
./scripts/m7-q02-convergence-gate.sh
node scripts/check-test-report-zero-skips.mjs

cd "$repository_root/crewscope-web"
pnpm test:coverage
pnpm build
pnpm story:build
pnpm test:e2e
pnpm audit --prod --audit-level=high --registry=https://registry.npmjs.org
cd "$repository_root"

docker build --platform linux/amd64 \
  --file deploy/team-beta/backend.Dockerfile \
  --tag crewscope-backend:demo .
docker build --platform linux/amd64 \
  --file deploy/team-beta/web.Dockerfile \
  --tag crewscope-web:demo .
./scripts/m8-q02-local-runtime-gate.sh

echo "M8-Q02 status: LOCAL_PRECHECK_PASS"
echo "Final release remains pending Linux amd64, GHCR/OIDC signing, real alert receiver, public TLS and production restore evidence."
