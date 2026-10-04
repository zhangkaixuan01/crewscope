#!/usr/bin/env node
/**
 * M10-A01 retrieval smoke gate over the preview endpoint (S01 §3.7).
 *
 * Third layer of the offline quality gate (plan ruling 9): while the JUnit layer
 * asserts the frozen recall thresholds against a dedicated database, this script
 * samples the ONE product HTTP entry — POST .../knowledge/knowledge-retrieval:preview —
 * and asserts the contract shape end to end: every answer is 200 + no-store (a
 * degraded source is a 200 with an explicit degraded code, never an error), the
 * response always carries {candidates, degraded, meta}, and latency percentiles are
 * recorded for the deployment under test.
 *
 * Requires a running stack:
 *   CREWSCOPE_KNOWLEDGE_RETRIEVAL_ENABLED=true CREWSCOPE_KNOWLEDGE_VECTOR_ENABLED=true \
 *   ./mvnw -pl crewscope-server -am spring-boot:run   (or the deployed BASE_URL)
 *
 * Usage:
 *   BASE_URL=http://127.0.0.1:8080 ORG=<uuid> TEAM=<uuid> TOKEN=<bearer> \
 *   [RETRIEVAL_PROJECT_ID=<uuid> RETRIEVAL_BINDING_ID=<uuid> RETRIEVAL_COMMIT=<40/64hex>] \
 *   node scripts/m10-a01/retrieval-quality-gate.mjs
 *
 * Exit codes: 0 = smoke passed; 1 = contract violation; 2 = missing environment.
 * The repository-domain sample needs the three RETRIEVAL_* coordinates and is
 * skipped (and reported) when they are absent. Degraded answers are legal states
 * of a closed or partially-wired switch — they are reported, not failed, unless
 * EXPECT_READY=1 pins the expectation that both sources answer.
 */
import { readFileSync } from 'node:fs';

const BASE_URL = process.env.BASE_URL;
const ORG = process.env.ORG;
const TEAM = process.env.TEAM;
const TOKEN = process.env.TOKEN;
const missing = ['BASE_URL', 'ORG', 'TEAM', 'TOKEN'].filter((n) => !process.env[n]);
if (missing.length) {
  console.error(`missing ${missing.join(', ')} — see the header comment for usage`);
  process.exit(2);
}

const DS = new URL('../m10-s01/dataset/', import.meta.url).pathname;
const SAMPLES_PER_DOMAIN = 5;
const dataset = (file) => JSON.parse(readFileSync(`${DS}${file}`, 'utf8'));
const knowledgeQueries = dataset('knowledge-queries.json').slice(0, SAMPLES_PER_DOMAIN);
const codeQueries = dataset('code-queries.json').slice(0, SAMPLES_PER_DOMAIN);
const unanswerable = dataset('unanswerable-queries.json').slice(0, SAMPLES_PER_DOMAIN);
const repositoryTarget = process.env.RETRIEVAL_PROJECT_ID && process.env.RETRIEVAL_BINDING_ID && process.env.RETRIEVAL_COMMIT
  ? { projectId: process.env.RETRIEVAL_PROJECT_ID, bindingId: process.env.RETRIEVAL_BINDING_ID, commit: process.env.RETRIEVAL_COMMIT }
  : null;
if (!repositoryTarget) {
  console.error('[gate] RETRIEVAL_PROJECT_ID/BINDING_ID/COMMIT absent — the repository-domain sample is skipped');
}

const previewUrl = `${BASE_URL.replace(/\/$/, '')}/api/v1/organizations/${ORG}/teams/${TEAM}/knowledge/knowledge-retrieval:preview`;
const failures = [];
const latencies = [];

async function preview(body) {
  const started = performance.now();
  const res = await fetch(previewUrl, {
    method: 'POST',
    headers: { Authorization: `Bearer ${TOKEN}`, 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  const latencyMs = performance.now() - started;
  const text = await res.text();
  let json;
  try { json = JSON.parse(text); } catch { json = { raw: text.slice(0, 200) }; }
  return { res, json, latencyMs };
}

function checkShape(label, run) {
  latencies.push(run.latencyMs);
  if (run.res.status !== 200) {
    failures.push(`${label}: expected HTTP 200, got ${run.res.status} (${JSON.stringify(run.json).slice(0, 160)})`);
    return;
  }
  const cacheControl = run.res.headers.get('cache-control') ?? '';
  if (!cacheControl.includes('no-store')) failures.push(`${label}: expected Cache-Control: no-store, got "${cacheControl}"`);
  if (!Array.isArray(run.json.candidates)) failures.push(`${label}: candidates must be an array`);
  if (!Array.isArray(run.json.degraded)) failures.push(`${label}: degraded must be an array`);
  if (typeof run.json.meta?.topK !== 'number') failures.push(`${label}: meta.topK must be a number`);
  for (const candidate of run.json.candidates ?? []) {
    if (!candidate.source || typeof candidate.rank !== 'number' || typeof candidate.score !== 'number') {
      failures.push(`${label}: candidate missing source/rank/score`);
      break;
    }
    if (candidate.source === 'KNOWLEDGE_ENTRY' && !candidate.entry) {
      failures.push(`${label}: KNOWLEDGE_ENTRY candidate without an entry projection`);
      break;
    }
    if (candidate.source === 'REPOSITORY_CHUNK' && !(candidate.fragments ?? []).length) {
      failures.push(`${label}: REPOSITORY_CHUNK candidate without fragments`);
      break;
    }
  }
  const entry = {
    label, status: run.res.status, latencyMs: +run.latencyMs.toFixed(1),
    candidates: run.json.candidates?.length ?? 0, degraded: run.json.degraded ?? [],
  };
  console.error(`[gate] ${label}: ${JSON.stringify(entry)}`);
  return entry;
}

const report = { endpoint: previewUrl.replace(/\/organizations\/[^/]+/, '/organizations/{org}'), samples: [] };

// Knowledge domain: the default body searches the reachable knowledge source only.
for (const q of knowledgeQueries) {
  report.samples.push(checkShape(`knowledge ${q.id}`, await preview({ query: q.query, topK: 8 })));
}

// Repository domain: explicit sources + the four-coordinate target.
if (repositoryTarget) {
  for (const q of codeQueries) {
    report.samples.push(checkShape(`code ${q.id}`, await preview({
      query: q.query, sources: ['REPOSITORY_CHUNK'], topK: 8, repository: repositoryTarget,
    })));
  }
}

// Unanswerable probes: the contract only pins the shape — the τ judgement lives
// in the JUnit gate; here an empty-or-scored answer is either way a legal 200.
for (const q of unanswerable) {
  report.samples.push(checkShape(`unanswerable ${q.id}`, await preview({ query: q.query, topK: 3 })));
}

// Malformed body: the one error contract the smoke pins — 400, not a 5xx, and
// definitely not a 200 that silently ignored the broken request.
{
  const run = await preview({ query: '', sources: ['MYSTERY'] });
  console.error(`[gate] malformed body: ${run.res.status}`);
  if (run.res.status !== 400) failures.push(`malformed body: expected HTTP 400, got ${run.res.status}`);
}

latencies.sort((a, b) => a - b);
const pick = (p) => +latencies[Math.min(latencies.length - 1, Math.floor(p * latencies.length))].toFixed(1);
report.latency = { n: latencies.length, p50: pick(0.5), p95: pick(0.95), p99: pick(0.99) };
report.degradedSeen = [...new Set(report.samples.flatMap((s) => s?.degraded ?? []))];

if (process.env.EXPECT_READY === '1' && report.degradedSeen.length) {
  failures.push(`EXPECT_READY=1 but the endpoint answered degraded: ${report.degradedSeen.join(',')}`);
}

if (failures.length) {
  console.error('[gate] FAILURES:');
  for (const failure of failures) console.error(`  - ${failure}`);
  process.exit(1);
}
console.log(JSON.stringify(report, null, 2));
console.error(`[gate] smoke passed: ${latencies.length} answers, all 200 + no-store.`);
