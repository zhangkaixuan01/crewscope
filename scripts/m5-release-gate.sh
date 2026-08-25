#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
sandbox_image="maven@sha256:29a1658b1f3078e07c2b17f7b519b45eb47f65d9628e887eac45a8c5c8f939d4"

cd "$repository_root"

command -v docker >/dev/null || {
  echo "Docker is required for the M5 release gate." >&2
  exit 1
}
command -v node >/dev/null || {
  echo "Node.js is required for the M5 release gate." >&2
  exit 1
}
command -v pnpm >/dev/null || {
  echo "pnpm is required for the M5 release gate." >&2
  exit 1
}
node -e "const major = Number(process.versions.node.split('.')[0]); if (major < 24) process.exit(1)" || {
  echo "Node.js 24 or newer is required for the M5 release gate." >&2
  exit 1
}
docker info >/dev/null
docker image inspect "$sandbox_image" >/dev/null || {
  echo "Required sandbox image is missing: $sandbox_image" >&2
  echo "Run: docker pull $sandbox_image" >&2
  exit 1
}

# Install the frozen dependency graph once before all Web subsets and browser checks.
cd "$repository_root/crewscope-web"
pnpm install --frozen-lockfile
cd "$repository_root"

node scripts/check-doc-links.mjs
node scripts/check-web-sensitive-fields.mjs
# Compare with HEAD so the local gate covers both staged and unstaged tracked changes.
git diff HEAD --check

# Untracked files are absent from the ordinary diff. Treat each as a /dev/null addition so local
# whitespace checks enforce the same rule as the eventual pushed patch.
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

# clean verify contains the complete M0-M5 backend, Flyway, AgentScope, GitHub fixture and Docker
# regression. The M5 gates then prove their frozen security, recovery and Reviewer matrices by name.
./mvnw --batch-mode --no-transfer-progress clean verify
./scripts/m5-q01-security-gate.sh
./scripts/m5-q02-fault-gate.sh
./scripts/m5-q03-evaluation-gate.sh

# M4 remains a release dependency. Compile its complete frozen Judge Pack independently so fixture
# source cannot silently drift while a historical real-model archive continues to pass.
evaluation_workspace="$(mktemp -d)"
trap 'rm -rf -- "$evaluation_workspace"' EXIT
node evaluation/m4/coding-v1/scripts/evaluate.mjs materialize --output "$evaluation_workspace"
mkdir -p "$evaluation_workspace/src/test/java/io/crewscope/evaluation"
cp evaluation/m4/coding-v1/judge-tests/*/*.java \
  "$evaluation_workspace/src/test/java/io/crewscope/evaluation/"
./mvnw --batch-mode --no-transfer-progress \
  --file "$evaluation_workspace/pom.xml" \
  -DskipTests test

cd "$repository_root/crewscope-web"
pnpm test:coverage
pnpm build
pnpm story:build
pnpm exec playwright install chromium
pnpm test:e2e
pnpm audit --prod --audit-level=high --registry=https://registry.npmjs.org

echo "M5 release gate passed."
