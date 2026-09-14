#!/usr/bin/env bash
set -euo pipefail

# M9-Q02 release gate.  `contracts-only` is safe on every developer machine;
# `local-precheck` additionally runs the complete local test/build contract.
repo_root="$(cd "$(dirname "$0")/.." && pwd)"
mode="${1:-contracts-only}"

cd "$repo_root"
node scripts/check-doc-links.mjs
node scripts/check-m9-q01.mjs
node scripts/check-openapi-drift.mjs
node scripts/check-config-contract.mjs

if [[ "$mode" == "contracts-only" ]]; then
  echo "M9-Q02 contracts-only gate: PASS"
  exit 0
fi

if [[ "$mode" != "local-precheck" ]]; then
  echo "usage: $0 [contracts-only|local-precheck]" >&2
  exit 2
fi

./mvnw --batch-mode --no-transfer-progress clean verify
pnpm --dir crewscope-web test:coverage
pnpm --dir crewscope-web build
pnpm --dir crewscope-web check:quality
pnpm --dir crewscope-web test:e2e
echo "M9-Q02 local-precheck gate: PASS"
