#!/usr/bin/env bash
set -euo pipefail

# M9-Q02 release gate.  `contracts-only` is safe on every developer machine;
# `local-precheck` additionally runs the complete local test/build contract.
repo_root="$(cd "$(dirname "$0")/.." && pwd)"
mode="${1:-contracts-only}"

cd "$repo_root"

# The file-contract checks the CI quality job runs.  Each one reads the repository and asserts; none
# needs Docker, a build or a browser, so the set costs seconds and belongs in front of a push.  The
# two M9 defects that reached CI — a blank line at end of file, and a viewport-scoped skip read as a
# disabled test — were both found by steps in this list, on a branch whose local gate had passed.
node scripts/check-doc-links.mjs
node scripts/check-openapi-drift.mjs
node scripts/check-config-contract.mjs
node scripts/check-team-beta-deployment.mjs
node scripts/check-team-beta-recovery.mjs
node scripts/check-release-contract.mjs
node scripts/check-m7-release-contract.mjs
node scripts/check-module-boundaries.mjs
node scripts/check-dependency-contract.mjs
node scripts/check-m9-q01.mjs
node scripts/check-design-tokens.mjs
node scripts/check-web-enum-labels.mjs
node scripts/check-empty-states.mjs
node scripts/check-route-metadata.mjs
node scripts/check-disabled-reasons.mjs
node scripts/check-web-pagination.mjs
node scripts/check-dialog-focus.mjs
node scripts/check-config-form-tokens.mjs
node scripts/check-web-sensitive-fields.mjs

# Counterpart of the CI patch-integrity step, which only ever sees the pushed range: the commits that
# have not gone out yet, plus whatever is still in the working tree.  This is the check that rejects
# a blank line at end of file, so it has to run before the commit that would introduce one.
git diff --check HEAD
if upstream="$(git rev-parse --verify --quiet origin/main)"; then
  git diff --check "$upstream"...HEAD
fi

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
node scripts/check-web-bundle-budget.mjs
pnpm --dir crewscope-web check:quality
pnpm --dir crewscope-web test:e2e
echo "M9-Q02 local-precheck gate: PASS"
