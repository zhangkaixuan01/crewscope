#!/usr/bin/env bash
set -euo pipefail

REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SANDBOX_IMAGE="maven@sha256:29a1658b1f3078e07c2b17f7b519b45eb47f65d9628e887eac45a8c5c8f939d4"

cd "$REPOSITORY_ROOT"

command -v docker >/dev/null || { echo "Docker is required for the M0 release gate." >&2; exit 1; }
docker info >/dev/null
docker image inspect "$SANDBOX_IMAGE" >/dev/null || {
  echo "Required sandbox image is missing: $SANDBOX_IMAGE" >&2
  echo "Run: docker pull $SANDBOX_IMAGE" >&2
  exit 1
}

node scripts/check-doc-links.mjs
./mvnw --batch-mode --no-transfer-progress clean verify

cd "$REPOSITORY_ROOT/crewscope-web"
pnpm install --frozen-lockfile
pnpm test:coverage
pnpm build
pnpm story:build
pnpm test:e2e
