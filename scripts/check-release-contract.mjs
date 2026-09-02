#!/usr/bin/env node

/** Static contract for the tag-driven public release workflow. */
import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { join, resolve } from 'node:path'

const root = resolve(import.meta.dirname, '..')
const workflowPath = join(root, '.github/workflows/release.yml')
assert.ok(existsSync(workflowPath), 'release workflow is missing')
const workflow = readFileSync(workflowPath, 'utf8')

for (const fragment of [
  "tags:\n      - 'v*.*.*'",
  'packages: write',
  'id-token: write',
  'attestations: write',
  'docker/build-push-action@v6',
  'docker/login-action@v3',
  'file: deploy/team-beta/backend.Dockerfile',
  'file: deploy/team-beta/web.Dockerfile',
  'platforms: linux/amd64',
  'provenance: mode=max',
  'sbom: true',
  'cosign sign --yes',
  'cosign verify',
  'release-manifest.json',
  'gh release create',
]) {
  assert.ok(workflow.includes(fragment), `release workflow is missing: ${fragment}`)
}
assert.equal((workflow.match(/provenance: mode=max/g) ?? []).length, 2)
assert.equal((workflow.match(/sbom: true/g) ?? []).length, 2)
assert.match(workflow, /backend_digest: \$\{\{ steps\.backend\.outputs\.digest \}\}/)
assert.match(workflow, /web_digest: \$\{\{ steps\.web\.outputs\.digest \}\}/)
assert.match(workflow, /CREWSCOPE_REVISION=\$\{\{ github\.sha \}\}/)
assert.match(workflow, /databaseSchemaVersion/)
assert.match(workflow, /schema_version=.*find crewscope-infrastructure\/src\/main\/resources\/db\/migration/)
assert.match(workflow, /--certificate-oidc-issuer https:\/\/token\.actions\.githubusercontent\.com/)

for (const [file, title] of [
  ['deploy/team-beta/backend.Dockerfile', 'CrewScope Backend'],
  ['deploy/team-beta/web.Dockerfile', 'CrewScope Web'],
]) {
  const dockerfile = readFileSync(join(root, file), 'utf8')
  assert.match(dockerfile, /ARG CREWSCOPE_REVISION=unknown/)
  assert.match(dockerfile, /ARG CREWSCOPE_VERSION=unknown/)
  assert.match(dockerfile, new RegExp(`org\\.opencontainers\\.image\\.title=\\"${title}\\"`))
  assert.match(dockerfile, /org\.opencontainers\.image\.revision=/)
  assert.match(dockerfile, /org\.opencontainers\.image\.version=/)
}

const readme = readFileSync(join(root, 'README.md'), 'utf8')
assert.match(readme, /GHCR|GitHub Release/)
assert.doesNotMatch(readme, /当前不会替部署方发布镜像/)

console.log('Release contract passed: tag validation, same-revision amd64 images, SBOM/Provenance and keyless signature verification.')
