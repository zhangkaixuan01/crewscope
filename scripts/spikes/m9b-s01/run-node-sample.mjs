// Runs only inside verify-isolated's disposable container; no product runtime is imported.
import { execFileSync } from 'node:child_process';
import assert from 'node:assert/strict';
import { canonicalProfile, nodeProfile, resolveDirectory } from './contract-prototype.mjs';

canonicalProfile(nodeProfile);
assert.equal(process.versions.node, nodeProfile.runtimeVersion);
assert.equal(execFileSync('npm', ['--version'], { encoding: 'utf8' }).trim(), nodeProfile.packageManagerVersion);
const cwd = resolveDirectory('/work', nodeProfile.directory);
for (const [kind, argv] of [
  ['PREPARE', nodeProfile.prepare], ...Object.entries(nodeProfile.commands),
]) {
  console.log(`NODE_PROFILE_${kind}`, JSON.stringify(argv));
  execFileSync(argv[0], argv.slice(1), {
    cwd, stdio: 'inherit', shell: false, timeout: nodeProfile.defaultTimeout * 1000,
  });
}
assert.throws(() => resolveDirectory('/work', 'escape'));
console.log('NODE_PROFILE_RUNTIME_AND_SYMLINK_BOUNDARY_OK');
