#!/usr/bin/env node
/**
 * M10-S01b embedding capability probe (DashScope OpenAI-compatible mode).
 *
 * Evidence entry for docs/spikes/M10-S01-知识与检索合同冻结.md §3.2. Re-runnable;
 * reads the API key from the file named by S01B_DASHSCOPE_KEY_FILE (required) in
 * "apikey:<value>" format. The key is never printed, logged or written to disk.
 *
 * Usage: S01B_DASHSCOPE_KEY_FILE=/path/to/key node scripts/m10-s01/embedding-probe.mjs
 */
import { readFileSync } from 'node:fs';

const KEY_FILE = process.env.S01B_DASHSCOPE_KEY_FILE;
if (!KEY_FILE) {
  console.error('missing S01B_DASHSCOPE_KEY_FILE (file containing "apikey:<value>")');
  process.exit(2);
}
const key = readFileSync(KEY_FILE, 'utf8').trim().split(':').slice(1).join(':');
if (!key.startsWith('sk-')) throw new Error('key file does not look like "apikey:sk-..."');

const BASE = 'https://dashscope.aliyuncs.com/compatible-mode/v1';
const MODEL = 'text-embedding-v4';

async function call(path, { method = 'GET', body, retries = 0 } = {}) {
  const started = performance.now();
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers: { Authorization: `Bearer ${key}`, 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if ((res.status === 429 || res.status >= 500) && retries > 0) {
    await sleep(1200);
    return call(path, { method, body, retries: retries - 1 });
  }
  const text = await res.text();
  let json;
  try { json = JSON.parse(text); } catch { json = { raw: text.slice(0, 300) }; }
  return { status: res.status, retryAfter: res.headers.get('retry-after'), latencyMs: performance.now() - started, json };
}

const embed = (input, extra = {}) => call('/embeddings', { method: 'POST', body: { model: MODEL, input, ...extra }, retries: 1 });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const vectorOf = (r) => r.json?.data?.map((d) => d.embedding) ?? null;
const allFinite = (vs) => vs?.every((v) => Array.isArray(v) && v.every(Number.isFinite)) ?? false;
const errBrief = (r) => (r.json?.error ? { code: r.json.error.code, type: r.json.error.type, message: String(r.json.error.message).slice(0, 160) } : undefined);

const report = { base: BASE, model: MODEL, cases: {} };

// 1. GET /models — does the listing cover the embedding model at all?
{
  const r = await call('/models');
  const ids = (r.json?.data ?? []).map((m) => m.id);
  report.cases.models_list = {
    status: r.status,
    listed: ids.length,
    lists_text_embedding_v4: ids.includes(MODEL),
    lists_any_embedding: ids.filter((id) => id.includes('embedding')).slice(0, 10),
  };
}

// 2. Default single embedding.
{
  const r = await embed(['向量化检索合同冻结：Team Knowledge 条目按发布版本生效']);
  const vs = vectorOf(r);
  report.cases.default_embed = {
    status: r.status, dim: vs?.[0]?.length ?? null, finite: allFinite(vs),
    usage: r.json?.usage, latencyMs: +r.latencyMs.toFixed(1), error: errBrief(r),
  };
}

// 3. dimensions parameter sweep.
{
  const dims = [64, 128, 256, 512, 768, 1024, 1536, 2048];
  const results = [];
  for (const d of dims) {
    const r = await embed([`维度档位探针 ${d}`], { dimensions: d });
    const vs = vectorOf(r);
    results.push({ requested: d, status: r.status, actualDim: vs?.[0]?.length ?? null, error: errBrief(r) });
    await sleep(300);
  }
  report.cases.dimensions_sweep = results;
}

// 4. Batch size sweep (short inputs keep cost negligible).
{
  const sizes = [8, 10, 16, 25, 32, 64];
  const results = [];
  for (const n of sizes) {
    const input = Array.from({ length: n }, (_, i) => `批量梯度探针 batch-${n} item-${i}`);
    const r = await embed(input);
    const vs = vectorOf(r);
    results.push({
      requested: n, status: r.status, returned: vs?.length ?? 0,
      usage: r.json?.usage ?? null, latencyMs: +r.latencyMs.toFixed(1), error: errBrief(r),
    });
    await sleep(500);
  }
  report.cases.batch_sweep = results;
}

// 5. Bilingual text with code — representative of the retrieval corpus.
{
  const code = 'class KnowledgeRetrievalQuery {\n  // 授权范围 = ExecutionScope ∩ Ownership\n  private final Scope scope;\n}';
  const r = await embed([`检索授权：${code}`]);
  const vs = vectorOf(r);
  report.cases.bilingual_code = {
    status: r.status, dim: vs?.[0]?.length ?? null, finite: allFinite(vs), usage: r.json?.usage, error: errBrief(r),
  };
}

// 6. Edge: empty string input.
{
  const r = await embed(['']);
  report.cases.edge_empty = { status: r.status, usage: r.json?.usage ?? null, error: errBrief(r) };
}

// 7. Edge: oversized input (v4 documented cap is per-item token limit; force a rejection).
{
  const huge = '超长输入探针。'.repeat(20000);
  const r = await embed([huge]);
  report.cases.edge_oversize = { status: r.status, chars: huge.length, error: errBrief(r) };
}

// 8. Rate-limit probe: 20 sequential minimal requests without pacing.
{
  const results = [];
  for (let i = 0; i < 20; i++) {
    const r = await embed([`限流探针 ${i}`]);
    results.push({ status: r.status, retryAfter: r.retryAfter, code: r.json?.error?.code ?? null });
    if (r.status === 429) await sleep(2000);
  }
  const ok = results.filter((r) => r.status === 200).length;
  report.cases.rate_limit_probe = {
    total: results.length, ok, rejected429: results.length - ok,
    first429: results.find((r) => r.status === 429) ?? null,
  };
}

// 9. Latency sample: 30 sequential single-item requests, paced lightly.
{
  const samples = [];
  for (let i = 0; i < 30; i++) {
    const r = await embed([`延迟采样探针 ${i}`]);
    if (r.status === 200) samples.push(r.latencyMs);
    await sleep(200);
  }
  samples.sort((a, b) => a - b);
  const pick = (p) => +samples[Math.min(samples.length - 1, Math.floor(p * samples.length))].toFixed(1);
  report.cases.latency_sample = { n: samples.length, p50: pick(0.5), p95: pick(0.95), p99: pick(0.99) };
}

console.log(JSON.stringify(report, null, 2));
