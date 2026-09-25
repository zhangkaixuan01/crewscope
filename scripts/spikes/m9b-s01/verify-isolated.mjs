// Local, offline S01 experiment. Starts only disposable containers, never Compose/product services.
import { execFileSync, spawn } from 'node:child_process';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { nodeImage, nodeProfile, profileHash } from './contract-prototype.mjs';

const root = fileURLToPath(new URL('../../../', import.meta.url));
const fixture = path.join(root, 'docs/spikes/fixtures/m9b-s01');
const prototype = path.join(root, 'scripts/spikes/m9b-s01');
const javaImage = 'maven@sha256:59a6335c47fc184e1755864431de23c4fb67e704d49474c624f1be194a9b980c';
const postgresImage = 'postgres@sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193';
function docker(args, input) {
  return execFileSync('docker', args, { encoding: 'utf8', input, timeout: 55000, maxBuffer: 2 * 1024 * 1024 });
}
const isolation = ['--pull=never', '--network=none', '--read-only', '--cap-drop=ALL', '--security-opt=no-new-privileges', '--cpus=2', '--memory=512m'];
const mount = ['--mount', `type=bind,source=${fixture},target=/fixtures,readonly`];
console.log('PROFILE_V2_SHA256', profileHash(nodeProfile));
console.log(docker(['run', '--rm', ...isolation, '--user=1000:1000',
  '--tmpfs=/work:rw,uid=1000,gid=1000,mode=0700', '--tmpfs=/tmp:rw,mode=1777',
  ...mount, '--mount', `type=bind,source=${prototype},target=/prototype,readonly`,
  '-e', 'npm_config_cache=/tmp/npm', '-w', '/work', nodeImage, 'sh', '-ec',
  'cp /fixtures/node/* /work/; node --version; npm --version; ln -s /etc /work/escape; node /prototype/run-node-sample.mjs']));
console.log(docker(['run', '--rm', ...isolation, '--platform=linux/amd64', '--entrypoint=sh', '--user=1000:1000',
  '--tmpfs=/work:rw,uid=1000,gid=1000,mode=0700', '--tmpfs=/tmp:rw,mode=1777',
  ...mount, '-w', '/work', javaImage, '-ec',
  'javac --release 17 -d /work /fixtures/java/Main.java; java -cp /work Main']));

let container;
function sql(text) {
  assert.match(container, /^[a-f0-9]{64}$/);
  return docker(['exec', '-i', container, 'psql', '-X', '-qAt', '-v', 'ON_ERROR_STOP=1', '-U', 'postgres'], text).trim();
}
function concurrentSql(text) {
  return new Promise((resolve, reject) => {
    const child = spawn('docker', ['exec', '-i', container, 'psql', '-X', '-qAt', '-v', 'ON_ERROR_STOP=1', '-U', 'postgres']);
    let error = '';
    child.stderr.on('data', data => { error += data; });
    child.stdout.resume();
    child.on('error', reject);
    child.on('exit', code => code === 0 ? resolve() : reject(new Error(error)));
    child.stdin.end(text);
  });
}
try {
  // postgres drops privilege during init; no host mounts, published ports, credentials or durable volume.
  container = docker(['run', '-d', '--rm', '--pull=never', '--network=none', '--read-only',
    '--security-opt=no-new-privileges', '--cpus=2', '--memory=512m',
    '--label=crewscope.spike=m9b-s01', '--tmpfs=/var/lib/postgresql/data:rw',
    '--tmpfs=/var/run/postgresql:rw', '--tmpfs=/tmp:rw',
    '-e', 'POSTGRES_HOST_AUTH_METHOD=trust', postgresImage]).trim();
  assert.match(container, /^[a-f0-9]{64}$/);
  let ready = false;
  for (let i = 0; i < 80; i++) {
    // initdb's temporary server accepts Unix sockets before the final server is ready.
    try { docker(['exec', container, 'pg_isready', '-h', '127.0.0.1', '-U', 'postgres']); ready = true; break; }
    catch { await new Promise(resolve => setTimeout(resolve, 250)); }
  }
  assert(ready, 'disposable postgres did not become ready');
  sql(fs.readFileSync(path.join(fixture, 'database.sql'), 'utf8'));
  console.log('PROVIDER_REVISION_FK_MIGRATION_AND_HISTORY_OK');
  console.log('INBOX_MIGRATION_PROTOTYPE_OK');
  await Promise.all([1, 2].map(id => concurrentSql(`
    BEGIN; SET LOCAL statement_timeout='10s';
    SELECT id FROM spike.team WHERE id=1 FOR UPDATE;
    UPDATE spike.member SET active=false WHERE id=${id}
      AND (SELECT count(*) FROM spike.member WHERE team_id=1 AND active AND owner)>1;
    COMMIT;`)));
  assert.equal(sql('SELECT count(*) FROM spike.member WHERE team_id=1 AND active AND owner;'), '1');
  assert.equal(sql('SELECT count(*) FROM spike.member WHERE team_id=2 AND active AND owner;'), '1');
  console.log('CONCURRENT_LAST_OWNER_AND_OTHER_TEAM_OK');
  const resume = `BEGIN;
    UPDATE spike.assignment a SET owner_id=2,version=a.version+1
      FROM spike.transfer_item i WHERE i.job=1 AND i.state='PENDING'
      AND a.id=i.assignment AND a.owner_id=1 AND a.version=i.old_version;
    UPDATE spike.transfer_item i SET state='DONE' FROM spike.assignment a
      WHERE i.assignment=a.id AND i.job=1 AND a.owner_id=2 AND a.version=i.old_version+1;
    COMMIT;`;
  sql(resume); sql(resume);
  assert.equal(sql('SELECT string_agg(version::text,\',\' ORDER BY id) FROM spike.assignment;'), '2,2');
  assert.equal(sql("SELECT count(*) FROM spike.transfer_item WHERE state='DONE';"), '2');
  console.log('TRANSFER_CHECKPOINT_REPLAY_OK');
  const plan = JSON.parse(sql("EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON) SELECT id,updated_at FROM spike.work WHERE org=1 AND team_id=1 ORDER BY updated_at DESC,id DESC LIMIT 20;"));
  assert(JSON.stringify(plan).includes('work_scope_page'), 'index not used');
  const page1 = sql('SELECT id FROM spike.work WHERE org=1 AND team_id=1 ORDER BY updated_at DESC,id DESC LIMIT 20;').split('\n');
  const last = Number(page1.at(-1));
  const page2 = sql(`SELECT id FROM spike.work WHERE org=1 AND team_id=1 AND (updated_at,id)<((SELECT updated_at FROM spike.work WHERE id=${last}),${last}) ORDER BY updated_at DESC,id DESC LIMIT 20;`).split('\n');
  assert.equal(new Set([...page1, ...page2]).size, 40);
  assert.deepEqual([...page1, ...page2], Array.from({ length: 40 }, (_, i) => String(10000 - i)));
  const times = [];
  for (let i = 0; i < 30; i++) {
    const run = JSON.parse(sql('EXPLAIN (ANALYZE,FORMAT JSON) SELECT id FROM spike.work WHERE org=1 AND team_id=1 ORDER BY updated_at DESC,id DESC LIMIT 20;'));
    if (i >= 10) times.push(run[0]['Execution Time']);
  }
  times.sort((a,b) => a-b);
  console.log('QUERY_PROTOTYPE', JSON.stringify({ rows: 10200, pageSize: 20, samples: times.length,
    sqlP95Ms: times[Math.ceil(times.length*0.95)-1], index: 'work_scope_page', cpus: 2, memoryMiB: 512 }));
  console.log('POSTGRES_VERSION', sql('SHOW server_version;'));
} finally {
  if (container && /^[a-f0-9]{64}$/.test(container)) {
    docker(['stop', '--time=5', container]);
    console.log('DISPOSABLE_POSTGRES_REMOVED');
  }
}
