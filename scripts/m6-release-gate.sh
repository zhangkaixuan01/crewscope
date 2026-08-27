#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
lane="${1:-local-preflight}"
case "$lane" in
  local-preflight|release-candidate) ;;
  *) echo "Usage: $0 [local-preflight|release-candidate]" >&2; exit 2 ;;
esac

sandbox_image="maven@sha256:29a1658b1f3078e07c2b17f7b519b45eb47f65d9628e887eac45a8c5c8f939d4"
cd "$repository_root"

for command in docker git node pnpm; do
  command -v "$command" >/dev/null || {
    echo "$command is required for the M6 release gate." >&2
    exit 1
  }
done
node -e "const major = Number(process.versions.node.split('.')[0]); if (major < 24) process.exit(1)" || {
  echo "Node.js 24 or newer is required for the M6 release gate." >&2
  exit 1
}
docker info >/dev/null
docker image inspect "$sandbox_image" >/dev/null || {
  echo "Required sandbox image is missing: $sandbox_image" >&2
  echo "Run: docker pull $sandbox_image" >&2
  exit 1
}

cd "$repository_root/crewscope-web"
[[ "$(pnpm --version)" == 11.9.0 ]] || {
  echo "M6 release gate requires the repository-pinned pnpm 11.9.0." >&2
  exit 1
}
pnpm install --frozen-lockfile
cd "$repository_root"

node scripts/check-doc-links.mjs
node scripts/check-team-beta-deployment.mjs
node scripts/check-team-beta-recovery.mjs
node scripts/check-web-sensitive-fields.mjs
node evaluation/m4/coding-v1/scripts/evaluate.mjs validate
node evaluation/m4/coding-q03/scripts/benchmark.mjs validate
node --test evaluation/m4/coding-q03/scripts/benchmark.test.mjs
node --test evaluation/m4/coding-q03/scripts/benchmark.integration.test.mjs
node evaluation/m5/reviewer-q03/scripts/benchmark.mjs validate
node --test evaluation/m5/reviewer-q03/scripts/benchmark.test.mjs

# Cover tracked, staged and untracked changes before spending time on the full regression.
git diff HEAD --check
untracked_whitespace_failed=0
while IFS= read -r -d '' untracked_file; do
  untracked_check="$(git diff --no-index --check /dev/null "$untracked_file" 2>&1 || true)"
  if [[ -n "$untracked_check" ]]; then
    printf '%s\n' "$untracked_check" >&2
    untracked_whitespace_failed=1
  fi
done < <(git ls-files --others --exclude-standard -z)
if [[ "$untracked_whitespace_failed" -ne 0 ]]; then
  exit 1
fi

# Run the latency-sensitive fixture before the long fault/migration suites. Its evidence lives
# outside Maven target trees so the required clean verify cannot erase the result.
q03_lane='fixture'
if [[ "$lane" == release-candidate ]]; then
  q03_lane='release-candidate'
fi
q04_evidence_root="$repository_root/var/release/m6-q04"
mkdir -p "$q04_evidence_root"
CREWSCOPE_M6_Q03_PROTOCOL_EVIDENCE="$q04_evidence_root/m6-q03-load-evidence.json" \
CREWSCOPE_M6_Q03_PRODUCTION_EVIDENCE="$q04_evidence_root/m6-q03-production-load-evidence.json" \
  ./scripts/m6-q03-gate.sh "$q03_lane"

./mvnw --batch-mode --no-transfer-progress clean verify
./scripts/m6-q01-security-gate.sh
./scripts/m6-q02-fault-gate.sh

# M4 remains a release dependency. Compile its frozen Judge Pack in a fresh materialization.
evaluation_workspace="$(mktemp -d)"
trap 'rm -rf -- "$evaluation_workspace"' EXIT
node evaluation/m4/coding-v1/scripts/evaluate.mjs materialize --output "$evaluation_workspace"
mkdir -p "$evaluation_workspace/src/test/java/io/crewscope/evaluation"
cp evaluation/m4/coding-v1/judge-tests/*/*.java \
  "$evaluation_workspace/src/test/java/io/crewscope/evaluation/"
./mvnw --batch-mode --no-transfer-progress \
  --file "$evaluation_workspace/pom.xml" \
  -DskipTests test

docker build --file deploy/team-beta/backend.Dockerfile --tag crewscope-backend:m6-q04 .
docker build --file deploy/team-beta/web.Dockerfile --tag crewscope-web:m6-q04 .

cd "$repository_root/crewscope-web"
pnpm test:coverage
pnpm build
pnpm story:build
pnpm audit --prod --audit-level=high --registry=https://registry.npmjs.org
cd "$repository_root"

if [[ "$lane" == local-preflight ]]; then
  echo "M6-Q04 local preflight passed; Canonical Q03 evidence and authoritative CI vulnerability scans remain required."
else
  echo "M6-Q04 release-candidate local gate passed; authoritative CI vulnerability scans remain required."
fi
