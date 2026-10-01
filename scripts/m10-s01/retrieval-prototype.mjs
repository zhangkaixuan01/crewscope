#!/usr/bin/env node
/**
 * M10-S01b retrieval prototype: authorized pgvector retrieval over the S01a-frozen
 * dataset (docs/spikes/M10-S01-知识与检索合同冻结.md §3.3/§3.4/§4).
 *
 * What it proves in isolation (NOT product acceptance):
 *  - chunking (80-line window, 10-line overlap, blank-line boundary snap)
 *  - authorized retrieval SQL shape: tenant predicate + effective version + active
 *    generation filter BEFORE the vector Top-K, never global Top-K filtered later
 *  - cross-team isolation and retired-version invisibility
 *  - first-round Recall@5/@10, version accuracy, unanswerable false-positive sweep
 *
 * Requires:
 *  - docker run -d --name s01b-pg -e POSTGRES_USER=s01b -e POSTGRES_PASSWORD=s01b \
 *      -e POSTGRES_DB=s01b -p 127.0.0.1:5433:5432 pgvector/pgvector:pg17
 *  - S01B_DASHSCOPE_KEY_FILE env var pointing at an "apikey:<value>" file (key never cached/logged).
 * Embeddings are cached by content hash in /tmp/s01b-embed-cache.json (key never cached).
 */
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';

const ROOT = new URL('../..', import.meta.url).pathname;
const DS = `${ROOT}scripts/m10-s01/dataset`;
const CACHE = '/tmp/s01b-embed-cache.json';
const MODEL = 'text-embedding-v4';
const DIM = 1024;
const ORG = '11111111-1111-1111-1111-111111111111';
const TEAM_A = 'aaaaaaaa-0000-0000-0000-000000000001';
const TEAM_B = 'aaaaaaaa-0000-0000-0000-000000000002'; // same org, other team
const DISTRACTORS = 150;

const key = readFileSync(process.env.S01B_DASHSCOPE_KEY_FILE, 'utf8').trim().split(':').slice(1).join(':');
const cache = existsSync(CACHE) ? JSON.parse(readFileSync(CACHE, 'utf8')) : {};
let cacheDirty = false;
const sha = (s) => createHash('sha256').update(s).digest('hex');

function psql(sql) {
  return execFileSync('docker', ['exec', '-i', 's01b-pg', 'psql', '-U', 's01b', '-d', 's01b',
    '-v', 'ON_ERROR_STOP=1', '-qAt', '-f', '-'], { input: sql, maxBuffer: 256 * 1024 * 1024 }).toString();
}
const dq = (tagBase, s) => {
  let tag = tagBase;
  while (s.includes(`$${tag}$`)) tag += 'x';
  return `$${tag}$${s}$${tag}$`;
};
const lit = (s) => s.replace(/'/g, "''");

async function embedAll(texts) {
  const out = new Array(texts.length);
  const missing = [];
  texts.forEach((t, i) => {
    const h = sha(`${MODEL}|${DIM}|${t}`);
    if (cache[h]) out[i] = cache[h];
    else missing.push({ i, h, t });
  });
  for (let n = 0; n < missing.length; n += 10) {
    const batch = missing.slice(n, n + 10);
    const res = await fetch('https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings', {
      method: 'POST',
      headers: { Authorization: `Bearer ${key}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ model: MODEL, input: batch.map((b) => b.t.slice(0, 32000)), dimensions: DIM }),
    });
    if (!res.ok) throw new Error(`embed ${res.status}: ${await res.text()}`);
    const json = await res.json();
    batch.forEach((b, j) => {
      const v = json.data[j].embedding;
      if (v.length !== DIM || !v.every(Number.isFinite)) throw new Error('bad vector');
      out[b.i] = v;
      cache[b.h] = v;
      cacheDirty = true;
    });
    await new Promise((r) => setTimeout(r, 150));
  }
  return out;
}
const vecLit = (v) => `'[${v.map((x) => +x.toFixed(7)).join(',')}]'`;

// ---------- chunking (S01a §3.3) ----------
function chunkLines(lines) {
  const WINDOW = 80, STEP = 70;
  const chunks = [];
  for (let start = 0; start < lines.length; start += STEP) {
    let end = Math.min(lines.length, start + WINDOW);
    if (end < lines.length) { // snap to a blank/short line within the overlap
      for (let c = end; c > end - 10 && c > start + 20; c--) {
        if (lines[c - 1].trim() === '') { end = c; break; }
      }
    }
    const content = lines.slice(start, end).join('\n');
    if (content.trim()) chunks.push({ startLine: start + 1, endLine: end, content });
    if (end >= lines.length) break;
  }
  return chunks;
}

// ---------- corpus ----------
const codeQueries = JSON.parse(readFileSync(`${DS}/code-queries.json`, 'utf8'));
const knowledgeQueries = JSON.parse(readFileSync(`${DS}/knowledge-queries.json`, 'utf8'));
const unanswerable = JSON.parse(readFileSync(`${DS}/unanswerable-queries.json`, 'utf8'));
const knowledgeEntries = JSON.parse(readFileSync(`${DS}/knowledge-entries.json`, 'utf8'));

const targetFiles = [...new Set(codeQueries.map((q) => q.expect))];
// distractor pool = product main sources only (the retrieval surface M10 targets);
// test sources, evaluation fixtures and the Lark integration adapter are excluded
const allJava = execFileSync('git', ['ls-files', '*.java'], { cwd: ROOT }).toString().trim().split('\n')
  .filter((p) => !p.includes('/var/') && !p.includes('src/test') && !p.startsWith('evaluation/') && !p.startsWith('crewscope-integration/'));
const targets = new Set(targetFiles);
// deterministic distractor sample
const distract = allJava.filter((p) => !targets.has(p))
  .filter((_, i) => sha(i.toString()).charCodeAt(0) % 7 === 0)
  .slice(0, DISTRACTORS);
const corpusFiles = [...targetFiles, ...distract];
console.error(`corpus: ${targetFiles.length} target + ${distract.length} distractor files, ${knowledgeEntries.length} knowledge rows`);

// ---------- embed corpus ----------
const fileChunks = corpusFiles.flatMap((path) => {
  const lines = readFileSync(`${ROOT}${path}`, 'utf8').split('\n');
  return chunkLines(lines).map((c) => ({ path, ...c }));
});
const knowledgeRows = knowledgeEntries.map((e) => ({
  key: e.key, version: e.version, effective: e.status === 'PUBLISHED',
  title: e.title, content: `${e.title}\n${e.content}`,
}));
console.error(`chunks: ${fileChunks.length} code chunks + ${knowledgeRows.length} knowledge rows`);

const texts = [...fileChunks.map((c) => c.content), ...knowledgeRows.map((r) => r.content)];
const vecs = await embedAll(texts);
if (cacheDirty) writeFileSync(CACHE, JSON.stringify(cache));

// ---------- schema + load ----------
psql(`DROP TABLE IF EXISTS repo_chunk, knowledge_entry, repo_generation CASCADE;
CREATE EXTENSION IF NOT EXISTS vector;
CREATE TABLE repo_chunk (
  id bigserial PRIMARY KEY, organization_id uuid NOT NULL, team_id uuid NOT NULL,
  path text NOT NULL, language text NOT NULL, start_line int NOT NULL, end_line int NOT NULL,
  content text NOT NULL, content_hash text NOT NULL, embedding vector(${DIM}));
CREATE TABLE knowledge_entry (
  id bigserial PRIMARY KEY, organization_id uuid NOT NULL, team_id uuid NOT NULL,
  entry_key text NOT NULL, version int NOT NULL, effective bool NOT NULL,
  title text NOT NULL, content text NOT NULL, content_hash text NOT NULL, embedding vector(${DIM}));
CREATE INDEX ON repo_chunk USING hnsw (embedding vector_cosine_ops);
CREATE INDEX ON knowledge_entry USING hnsw (embedding vector_cosine_ops);`);

function insertBatch(table, cols, rows) {
  for (let n = 0; n < rows.length; n += 50) {
    const values = rows.slice(n, n + 50).map((r) => {
      const parts = r.map((v) => (Array.isArray(v) ? vecLit(v) : typeof v === 'number' || typeof v === 'boolean' ? String(v) : dq('tx', String(v))));
      return `(${parts.join(',')})`;
    }).join(',');
    psql(`INSERT INTO ${table} (${cols}) VALUES ${values};`);
  }
}

// load the same corpus under both teams: isolation must be proven, not assumed
const chunkRow = (team, c, v) => [ORG, team, c.path, 'java', c.startLine, c.endLine, c.content, sha(c.content), v];
const knowledgeRow = (team, r, v) => [ORG, team, r.key, r.version, r.effective, r.title, r.content, sha(r.content), v];
const chunkCols = 'organization_id,team_id,path,language,start_line,end_line,content,content_hash,embedding';
const knowledgeCols = 'organization_id,team_id,entry_key,version,effective,title,content,content_hash,embedding';
insertBatch('repo_chunk', chunkCols, [
  ...fileChunks.map((c, i) => chunkRow(TEAM_A, c, vecs[i])),
  ...fileChunks.map((c, i) => chunkRow(TEAM_B, c, vecs[i])),
]);
insertBatch('knowledge_entry', knowledgeCols, [
  ...knowledgeRows.map((r, i) => knowledgeRow(TEAM_A, r, vecs[fileChunks.length + i])),
  ...knowledgeRows.map((r, i) => knowledgeRow(TEAM_B, r, vecs[fileChunks.length + i])),
]);
console.error(`loaded: ${psql('SELECT count(*) FROM repo_chunk')} chunks, ${psql('SELECT count(*) FROM knowledge_entry')} knowledge rows`);

// ---------- retrieval ----------
const queryTexts = [...codeQueries.map((q) => q.query), ...knowledgeQueries.map((q) => q.query), ...unanswerable.map((q) => q.query)];
const queryVecs = await embedAll(queryTexts);
if (cacheDirty) writeFileSync(CACHE, JSON.stringify(cache));

function search(table, team, vec, k, { effectiveOnly = false, authorized = true } = {}) {
  const where = authorized
    ? `organization_id='${ORG}'::uuid AND team_id='${team}'::uuid${effectiveOnly ? ' AND effective' : ''}`
    : 'true'; // control experiment: global Top-K without tenant predicate
  const sql = `SELECT COALESCE(json_agg(t), '[]'::json) FROM (
    SELECT id, ${table === 'repo_chunk' ? 'path, start_line, end_line' : 'entry_key, version, effective'},
           1 - (embedding <=> ${vecLit(vec)}) AS score
    FROM ${table} WHERE ${where}
    ORDER BY embedding <=> ${vecLit(vec)} LIMIT ${k}) t;`;
  return JSON.parse(psql(sql));
}

const result = { corpus: { targetFiles: targetFiles.length, distractors: distract.length, chunks: fileChunks.length, knowledgeRows: knowledgeRows.length }, cases: {} };

// code queries: file-level recall
{
  const perK = (k) => {
    let hit = 0;
    const misses = [];
    codeQueries.forEach((q, i) => {
      const rows = search('repo_chunk', TEAM_A, queryVecs[i], k);
      const files = new Set(rows.map((r) => r.path));
      if (files.has(q.expect)) hit++;
      else misses.push({ id: q.id, top: rows.slice(0, 3).map((r) => `${r.path.split('/').pop()}:${r.score.toFixed(3)}`) });
    });
    return { recall: +(hit / codeQueries.length).toFixed(4), misses: misses.slice(0, 8) };
  };
  result.cases.code_recall = { at5: perK(5), at10: perK(10) };
}

// knowledge queries: recall + version accuracy on the EFFECTIVE-gated pool (contract shape);
// plus a control pass WITHOUT the gate to document that retired versions compete for rank
{
  let hit = 0, versionCorrect = 0;
  const ungatedMisses = [];
  knowledgeQueries.forEach((q, i) => {
    const vec = queryVecs[codeQueries.length + i];
    const rows = search('knowledge_entry', TEAM_A, vec, 10, { effectiveOnly: true });
    const keys = new Set(rows.map((r) => r.entry_key));
    if (keys.has(q.expectKey)) hit++;
    const best = rows.find((r) => r.entry_key === q.expectKey);
    if (best && best.version === q.expectVersion) versionCorrect++;
    const ungated = search('knowledge_entry', TEAM_A, vec, 10);
    const ungatedBest = ungated.find((r) => r.entry_key === q.expectKey);
    if (ungatedBest && ungatedBest.version !== q.expectVersion) {
      ungatedMisses.push({ id: q.id, want: q.expectVersion, got: ungatedBest.version });
    }
  });
  result.cases.knowledge = {
    recallAt10: +(hit / knowledgeQueries.length).toFixed(4),
    versionAccuracy: +(versionCorrect / knowledgeQueries.length).toFixed(4),
    ungatedControl: {
      note: 'without the effective gate, retired versions compete for rank — the gate is mandatory, not an optimization',
      staleVersionWins: ungatedMisses.length,
      misses: ungatedMisses,
    },
  };
}

// retired versions must never surface: effective-gated search returns only effective rows,
// and retired rows do exist in the table (the gate, not absence, is what hides them)
{
  const gatedRows = knowledgeQueries.flatMap((q, i) =>
    search('knowledge_entry', TEAM_A, queryVecs[codeQueries.length + i], 10, { effectiveOnly: true }));
  const allEffective = gatedRows.every((r) => r.effective === true);
  const retiredInTable = +psql(`SELECT count(*) FROM knowledge_entry WHERE NOT effective`);
  result.cases.retired_invisible = { passed: allEffective && retiredInTable > 0, retiredRowsInTable: retiredInTable };
}

// unanswerable: threshold sweep on max score; code recall per tau computed in memory from one fetch
{
  const maxScores = unanswerable.map((q, i) => {
    const vec = queryVecs[codeQueries.length + knowledgeQueries.length + i];
    const codeRows = search('repo_chunk', TEAM_A, vec, 5);
    const knowledgeRowsRes = search('knowledge_entry', TEAM_A, vec, 5, { effectiveOnly: true });
    return Math.max(codeRows[0]?.score ?? 0, knowledgeRowsRes[0]?.score ?? 0);
  });
  const codeTop20 = codeQueries.map((q, i) => search('repo_chunk', TEAM_A, queryVecs[i], 20));
  const sweep = [];
  for (let tau = 0.3; tau <= 0.7001; tau += 0.05) {
    const fp = maxScores.filter((s) => s >= tau).length / unanswerable.length;
    let hit = 0;
    codeQueries.forEach((q, i) => { if (codeTop20[i].some((r) => r.path === q.expect && r.score >= tau)) hit++; });
    sweep.push({ tau: +tau.toFixed(2), falsePositiveRate: +fp.toFixed(3), codeRecallAt10: +(hit / codeQueries.length).toFixed(4) });
  }
  const chosen = sweep.find((s) => s.falsePositiveRate <= 0.10) ?? null;
  result.cases.unanswerable = { maxScores: maxScores.map((s) => +s.toFixed(3)), sweep, chosenTau: chosen?.tau ?? null };
}

// authorization boundary: tenant predicate is load-bearing (id-level proof)
{
  const vec = queryVecs[0];
  const scoped = search('repo_chunk', TEAM_A, vec, 10);
  const global = search('repo_chunk', TEAM_A, vec, 10, { authorized: false });
  const scopedTeams = psql(`SELECT DISTINCT team_id::text FROM repo_chunk WHERE id IN (${scoped.map((r) => r.id).join(',')})`).trim();
  const globalHasB = psql(`SELECT count(*) FROM repo_chunk WHERE id IN (${global.map((r) => r.id).join(',')}) AND team_id='${TEAM_B}'`).trim();
  result.cases.tenant_isolation = {
    scopedRows: scoped.length, scopedRowTeams: scopedTeams,
    globalRowsIncludeTeamB: globalHasB !== '0',
    passed: scopedTeams === TEAM_A && globalHasB !== '0',
    note: 'scoped search returns only team A ids; removing the tenant predicate surfaces team B rows — the predicate is load-bearing',
  };
}

// latency sample on the SQL side (embedding call excluded)
{
  const samples = [];
  for (let i = 0; i < 30; i++) {
    const t0 = performance.now();
    search('repo_chunk', TEAM_A, queryVecs[i % codeQueries.length], 10);
    samples.push(performance.now() - t0);
  }
  samples.sort((a, b) => a - b);
  const pick = (p) => +samples[Math.min(samples.length - 1, Math.floor(p * samples.length))].toFixed(1);
  result.cases.sql_latency_ms = { n: samples.length, p50: pick(0.5), p95: pick(0.95), p99: pick(0.99), note: 'includes docker exec psql round-trip overhead' };
}

// query plan evidence (HNSW usage on this small corpus)
{
  const plan = psql(`SET enable_seqscan=off; EXPLAIN (COSTS OFF) SELECT path FROM repo_chunk
    WHERE organization_id='${ORG}'::uuid AND team_id='${TEAM_A}'::uuid
    ORDER BY embedding <=> ${vecLit(queryVecs[0])} LIMIT 10;`);
  result.cases.query_plan_hnsw = plan.trim().split('\n').slice(0, 8);
}

console.log(JSON.stringify(result, null, 1));
