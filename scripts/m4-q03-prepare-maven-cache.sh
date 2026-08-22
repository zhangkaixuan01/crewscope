#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repository_root"

if [[ "$#" -ne 2 ]]; then
  echo "Usage: $0 <empty-output-directory> <snapshot-id>" >&2
  exit 1
fi

cache_root="$1"
snapshot_id="$2"
if [[ -e "$cache_root" && ! -d "$cache_root" ]]; then
  echo "Cache output is not a directory: $cache_root" >&2
  exit 1
fi
if [[ -d "$cache_root" && -n "$(find "$cache_root" -mindepth 1 -print -quit)" ]]; then
  echo "Cache output must be empty: $cache_root" >&2
  exit 1
fi

mkdir -p "$cache_root/repository"
cache_root="$(cd "$cache_root" && pwd)"
temporary_root="$(mktemp -d)"
trap 'rm -rf -- "$temporary_root"' EXIT
workspace="$temporary_root/workspace"

node evaluation/m4/coding-v1/scripts/evaluate.mjs materialize --output "$workspace"
mkdir -p "$workspace/src/test/java/io/crewscope/evaluation"
cp evaluation/m4/coding-v1/judge-tests/*/*.java \
  "$workspace/src/test/java/io/crewscope/evaluation/"

# The snapshot is populated before it becomes immutable. Benchmark Sandboxes later mount this
# directory read-only and run Maven offline with /maven-cache/repository as the local repository.
./mvnw --batch-mode --no-transfer-progress \
  --file "$workspace/pom.xml" \
  -Dmaven.repo.local="$cache_root/repository" \
  dependency:go-offline test-compile

# Surefire resolves its JUnit Platform provider only when tests actually start. Execute the
# frozen Judge Pack once while the cache is still writable; baseline assertion failures are
# expected, but dependency-resolution failures are not. The immutable benchmark Sandboxes can
# then run the same tests without attempting to create tracking files in the read-only cache.
./mvnw --batch-mode --no-transfer-progress \
  --file "$workspace/pom.xml" \
  -Dmaven.repo.local="$cache_root/repository" \
  -Dmaven.test.failure.ignore=true \
  test

find "$cache_root" -exec chmod a-w {} +
node evaluation/m4/coding-q03/scripts/benchmark.mjs snapshot-cache \
  --source "$cache_root" \
  --output "$cache_root.manifest.json" \
  --snapshot-id "$snapshot_id"

echo "M4-Q03 Maven cache prepared at $cache_root"
echo "Manifest: $cache_root.manifest.json"
