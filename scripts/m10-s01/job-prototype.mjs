#!/usr/bin/env node
/**
 * M10-S01b durable index-job prototype (docs/spikes/M10-S01-知识与检索合同冻结.md §3.3).
 *
 * Isolated SQL proof of the frozen job contract, borrowing the
 * GitHubRepositoryImportWorker shape with the two frozen deltas:
 *  - monotonic claim_token fencing: a stale claim's writes are rejected with a
 *    DETECTABLE zero-row update (never a silent progress stall)
 *  - per-chunk checkpoints: a restarted job resumes after the last committed
 *    batch instead of redoing work
 *
 * Scenarios: S1 concurrent claim; S2 stale-token fencing; S3 lease expiry +
 * resume past committed checkpoints + late checkpoint rejected; S4 renewal on
 * progress and lease cleared on terminal state.
 */
import { execFileSync, spawn } from 'node:child_process';

const runAsync = (args) => new Promise((resolve) => {
  const p = spawn('docker', args);
  let out = '';
  let err = '';
  p.stdout.on('data', (d) => { out += d; });
  p.stderr.on('data', (d) => { err += d; });
  p.on('close', (code) => resolve({ code, out: out.trim(), err: err.trim() }));
});

const psql = (sql) => execFileSync('docker', ['exec', '-i', 's01b-pg', 'psql', '-U', 's01b', '-d', 's01b',
  '-v', 'ON_ERROR_STOP=1', '-qAt', '-c', sql], { maxBuffer: 16 * 1024 * 1024 }).toString().trim();
const LEASE = "interval '600 seconds'";

psql(`DROP TABLE IF EXISTS s01b_index_checkpoint, s01b_index_job CASCADE;
CREATE TABLE s01b_index_job (
  id bigserial PRIMARY KEY,
  index_key text NOT NULL UNIQUE,
  status text NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED','CHUNKING','EMBEDDING','ACTIVATING','READY','FAILED','CANCELLED')),
  progress_percent int NOT NULL DEFAULT 0,
  attempt int NOT NULL DEFAULT 0,
  claim_token bigint NOT NULL DEFAULT 0,
  lease_owner text, lease_expires_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE s01b_index_checkpoint (
  job_id bigint NOT NULL REFERENCES s01b_index_job(id),
  chunk_seq int NOT NULL, claim_token bigint NOT NULL, state text NOT NULL,
  completed_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (job_id, chunk_seq));`);

const claimSql = (owner) => `WITH candidate AS (
  SELECT id FROM s01b_index_job
  WHERE status='QUEUED' OR (status IN ('CHUNKING','EMBEDDING') AND lease_expires_at <= now())
  ORDER BY created_at, id FOR UPDATE SKIP LOCKED LIMIT 1)
UPDATE s01b_index_job j SET status='EMBEDDING', attempt=attempt+1, claim_token=claim_token+1,
  lease_owner='${owner}', lease_expires_at=now()+${LEASE}, progress_percent=GREATEST(progress_percent,10)
FROM candidate WHERE j.id=candidate.id RETURNING j.id||':'||j.claim_token;`;
const claim = (owner) => psql(claimSql(owner));

// progress advance doubles as lease renewal; zero rows = fenced (detectable)
const advance = (id, token, owner, pct) => psql(`UPDATE s01b_index_job SET progress_percent=${pct},
  lease_expires_at=now()+${LEASE} WHERE id=${id} AND claim_token=${token} AND lease_owner='${owner}'
  AND status IN ('CHUNKING','EMBEDDING','ACTIVATING') AND lease_expires_at > now() RETURNING id;`);

// checkpoint insert is fenced by the job's CURRENT token
const checkpoint = (id, token, seq) => psql(`INSERT INTO s01b_index_checkpoint (job_id, chunk_seq, claim_token, state)
  SELECT ${id}, ${seq}, ${token}, 'DONE' WHERE EXISTS (
  SELECT 1 FROM s01b_index_job WHERE id=${id} AND claim_token=${token}) RETURNING job_id||':'||chunk_seq;`);

const expireLease = (id) => psql(`UPDATE s01b_index_job SET lease_expires_at=now()-interval '1 second' WHERE id=${id} RETURNING id;`);
const job = (id) => JSON.parse(psql(`SELECT row_to_json(t) FROM (
  SELECT status, progress_percent, attempt, claim_token, lease_owner,
         lease_expires_at::text AS lease_expires_at FROM s01b_index_job WHERE id=${id}) t;`));

const report = { scenarios: {} };
const expect = (name, cond, detail) => { if (!cond) throw new Error(`ASSERT ${name}: ${detail}`); };

// S1: exactly one of two genuinely concurrent claims wins (overlapping txns + SKIP LOCKED)
psql(`INSERT INTO s01b_index_job (index_key) VALUES ('org/t1/b1/commit1/policy1/emb1');`);
const jobId = +psql('SELECT id FROM s01b_index_job WHERE index_key=\'org/t1/b1/commit1/policy1/emb1\';');
const claimTxArgs = (owner) => ['exec', '-i', 's01b-pg', 'psql', '-U', 's01b', '-d', 's01b',
  '-qAt', '-v', 'ON_ERROR_STOP=1',
  '-c', 'BEGIN', '-c', 'SELECT pg_sleep(1)', '-c', claimSql(owner), '-c', 'COMMIT'];
const [resA, resB] = await Promise.all([
  runAsync(claimTxArgs('workerA')), runAsync(claimTxArgs('workerB')),
]);
const claimLines = [...resA.out.split('\n'), ...resB.out.split('\n')].filter((l) => l.includes(':'));
expect('S1-txns-ok', resA.code === 0 && resB.code === 0, `exit ${resA.code}/${resB.code} err=${resA.err} | ${resB.err}`);
expect('S1-one-winner', claimLines.length === 1, `claims=${JSON.stringify(claimLines)}`);
const claims = claimLines;
const [firstId, firstToken] = claims[0].split(':').map(Number);
expect('S1-job-id', firstId === jobId, `id ${firstId}`);
const winner = job(jobId).lease_owner;
report.scenarios.s1_concurrent_claim = { winner, claimToken: job(jobId).claim_token, attempt: job(jobId).attempt };

// S2: lease expiry -> reclaim bumps token; the stale holder's writes are rejected detectably
const before = job(jobId);
const leaseBefore = before.lease_expires_at;
expireLease(jobId);
const reclaim = claim('workerB');
expect('S2-reclaim', reclaim.startsWith(`${jobId}:`), reclaim);
const reclaimed = job(jobId);
expect('S2-token-bump', reclaimed.claim_token === before.claim_token + 1, `${before.claim_token} -> ${reclaimed.claim_token}`);
const staleAdvance = advance(jobId, before.claim_token, before.lease_owner, 50);
expect('S2-stale-rejected', staleAdvance === '', `stale advance returned ${staleAdvance}`);
expect('S2-progress-untouched', job(jobId).progress_percent === reclaimed.progress_percent, 'progress mutated by stale writer');
report.scenarios.s2_fencing = { staleTokenRejected: true, token: reclaimed.claim_token, holder: reclaimed.lease_owner, leaseRenewedAtReclaim: reclaimed.lease_expires_at !== leaseBefore };

// S3: checkpoints survive reclaim; resume continues after the last committed batch; late checkpoint rejected
for (const seq of [1, 2, 3]) checkpoint(jobId, reclaimed.claim_token, seq);
expect('S3-checkpoints', +psql(`SELECT count(*) FROM s01b_index_checkpoint WHERE job_id=${jobId}`) === 3, 'checkpoint count');
expireLease(jobId);
const third = claim('workerC');
expect('S3-third-claim', third.startsWith(`${jobId}:`), third);
const tokenC = job(jobId).claim_token;
const maxSeq = +psql(`SELECT COALESCE(max(chunk_seq),0) FROM s01b_index_checkpoint WHERE job_id=${jobId}`);
expect('S3-resume-from-4', maxSeq === 3, `maxSeq=${maxSeq}`);
const lateCp = checkpoint(jobId, reclaimed.claim_token, 4); // workerB's late write with old token
expect('S3-late-checkpoint-rejected', lateCp === '', `late insert returned ${lateCp}`);
expect('S3-no-late-row', +psql(`SELECT count(*) FROM s01b_index_checkpoint WHERE job_id=${jobId} AND chunk_seq=4`) === 0, 'late row exists');
report.scenarios.s3_resume = { committedCheckpoints: maxSeq, resumeAfter: maxSeq + 1, lateWriterRejected: true, thirdToken: tokenC };

// S4: progress advances renew the lease; terminal state clears it
const t0 = job(jobId).lease_expires_at;
advance(jobId, tokenC, 'workerC', 60);
const t1 = job(jobId).lease_expires_at;
expect('S4-renew', t1 >= t0, `${t0} -> ${t1}`);
psql(`UPDATE s01b_index_job SET status='READY', progress_percent=100, lease_owner=NULL, lease_expires_at=NULL WHERE id=${jobId};`);
const done = job(jobId);
expect('S4-terminal-clears-lease', done.lease_owner === null && done.lease_expires_at === null, 'lease not cleared');
report.scenarios.s4_renew_and_terminal = { renewed: t1 >= t0, terminalStatus: done.status, leaseCleared: true };

console.log(JSON.stringify(report, null, 1));
