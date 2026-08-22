#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
sandbox_image="maven@sha256:29a1658b1f3078e07c2b17f7b519b45eb47f65d9628e887eac45a8c5c8f939d4"

cd "$repository_root"

command -v docker >/dev/null || {
  echo "Docker is required for the M4 release gate." >&2
  exit 1
}
command -v node >/dev/null || {
  echo "Node.js is required for the M4 release gate." >&2
  exit 1
}
command -v pnpm >/dev/null || {
  echo "pnpm is required for the M4 release gate." >&2
  exit 1
}
node -e "const major = Number(process.versions.node.split('.')[0]); if (major < 24) process.exit(1)" || {
  echo "Node.js 24 or newer is required for the M4 release gate." >&2
  exit 1
}
docker info >/dev/null
docker image inspect "$sandbox_image" >/dev/null || {
  echo "Required sandbox image is missing: $sandbox_image" >&2
  echo "Run: docker pull $sandbox_image" >&2
  exit 1
}

# Install once before the Q01/Q02 Web subsets and the complete frontend gate. The frozen lockfile
# makes all later browser, coverage, workbench and audit decisions use the same dependency graph.
cd "$repository_root/crewscope-web"
pnpm install --frozen-lockfile
cd "$repository_root"

# M4 releases as one backend, Coding security/recovery/quality and browser decision. Q03 validates
# the protocol and deterministic integration corpus; the archived credentialed aggregate remains
# immutable evidence and is not rerun against an external model during ordinary release checks.
node scripts/check-doc-links.mjs
git diff --check

# git diff ignores files that have not entered the index yet. Check each untracked source as a
# /dev/null addition so the local gate catches the same whitespace errors as the pushed CI patch.
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

./mvnw --batch-mode --no-transfer-progress clean verify
./scripts/m4-q01-security-gate.sh
./scripts/m4-q02-fault-gate.sh
./scripts/m4-q03-evaluation-gate.sh

# Compile the complete frozen Judge Pack independently from the real-model archive so committed
# source fixtures cannot drift into a state that only a previously prepared Maven cache can build.
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

echo "M4 release gate passed."
