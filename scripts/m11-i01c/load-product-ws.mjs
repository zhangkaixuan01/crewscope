#!/usr/bin/env node
// M11-I01c product-stack collaboration load generator (I01c evidence).
//
// Drives the REAL four-service team-beta stack over the published Nginx entry point —
// no probe endpoints, no fixture fakes. Accounts are created through the public
// registration + onboarding API (OPEN mode), every WebSocket rides
// /api/v1/collaboration/ws through Nginx's upgrade routing, and the leak assertion
// reads the stack's own Redis.
//
// Node's global WebSocket (undici) cannot attach a Cookie header, and the slow-client
// drill needs direct socket control, so this carries the minimal RFC 6455 client from
// the S01b probe forward (masked text frames out, unmasked frames in, close-code
// capture). The protocol here is the I01 product protocol: server pings, the client
// answers pong; the steady-state latency probe rides idempotent re-subscribes because
// the product protocol has no client-initiated ping verb.
//
// Per-principal admission caps at 16 connections, so 200 connections need at least
// 13 principals; credentials are cached in a run file and re-login refreshes cookies
// on every invocation (sessions age, usernames do not).
//
// Prerequisites: the gate stack (scripts/m11-i01c-real-stack-gate.sh) up with
// CREWSCOPE_COLLABORATION_REALTIME_ENABLED=true and a compressed heartbeat for the
// slow1013 drill. Requires node >= 18.
//
// Usage: node scripts/m11-i01c/load-product-ws.mjs
//          [--scenario storm|steady|reconnect|slow1013|leak]
//          [--count 200] [--duration 120]
//          [--base-url http://127.0.0.1:18087]
//          [--redis-container m11-i01c-redis-1]
//          [--api-container m11-i01c-api-1]
//          [--principals-file var/release/m11-i01c/load-principals.json]

import http from "node:http";
import crypto from "node:crypto";
import { execFile } from "node:child_process";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);

const WS_PATH = "/api/v1/collaboration/ws";
const MAX_CONNECTIONS_PER_PRINCIPAL = 16;

const args = process.argv.slice(2);
function argOf(name, fallback) {
  const i = args.indexOf(`--${name}`);
  return i >= 0 && i + 1 < args.length ? args[i + 1] : fallback;
}
const SCENARIO = argOf("scenario", "storm");
const COUNT = Number(argOf("count", 200));
const DURATION = Number(argOf("duration", 120)) * 1000;
const BASE_URL = new URL(argOf("base-url", "http://127.0.0.1:18087"));
const REDIS_CONTAINER = argOf("redis-container", "m11-i01c-redis-1");
const API_CONTAINER = argOf("api-container", "m11-i01c-api-1");
const PRINCIPALS_FILE = resolve(argOf("principals-file", "var/release/m11-i01c/load-principals.json"));

const HOST = BASE_URL.hostname;
const PORT = Number(BASE_URL.port);
const ORIGIN = BASE_URL.origin;

function percentile(values, p) {
  if (!values.length) return NaN;
  const sorted = [...values].sort((a, b) => a - b);
  const idx = Math.min(sorted.length - 1, Math.max(0, Math.ceil((p / 100) * sorted.length) - 1));
  return sorted[idx];
}

function reportTimings(label, values) {
  console.log(`${label}: n=${values.length} p50=${percentile(values, 50).toFixed(1)}ms `
      + `p95=${percentile(values, 95).toFixed(1)}ms p99=${percentile(values, 99).toFixed(1)}ms`);
}

function httpRequest(method, path, body, headers = {}) {
  return new Promise((resolve, reject) => {
    const payload = body === undefined ? null : Buffer.from(JSON.stringify(body));
    const req = http.request({ host: HOST, port: PORT, path, method, headers: {
      ...(payload ? { "Content-Type": "application/json", "Content-Length": payload.length } : {}),
      ...headers } }, res => {
      let data = "";
      const cookie = (res.headers["set-cookie"] || []).map(c => c.split(";")[0]).join("; ");
      res.on("data", chunk => data += chunk);
      res.on("end", () => resolve({ status: res.statusCode, body: data, cookie }));
    });
    req.on("error", reject);
    if (payload) req.write(payload);
    req.end();
  });
}

function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

// ------------------------------------------------------------- account factory

/** Anonymous bootstrap: a CSRF double-submit pair plus the cookie jar it arrived in. */
async function anonymousSession() {
  const res = await httpRequest("GET", "/api/v1/auth/session");
  if (res.status !== 200) throw new Error(`session bootstrap failed: ${res.status} ${res.body}`);
  const body = JSON.parse(res.body);
  const csrf = body.csrf && body.csrf.token ? body.csrf : null;
  if (!csrf) throw new Error("session bootstrap returned no CSRF coordinates");
  return { cookies: res.cookie, csrf };
}

function csrfHeaders({ cookies, csrf }) {
  return { Cookie: cookies, [csrf.headerName]: csrf.token };
}

/** Registers one OPEN account, onboards its first Team, and returns login credentials. */
async function createPrincipal(index, runTag) {
  const suffix = `${runTag}-${index}`;
  const username = `m11load-${suffix}`.slice(0, 64);
  const email = `m11load-${suffix}@example.test`;
  const password = `Load-${suffix}-Horse-Battery`;
  const bootstrap = await anonymousSession();
  const register = await httpRequest("POST", "/api/v1/auth/register",
      { username, email, displayName: `M11 Load ${suffix}`, password },
      { ...csrfHeaders(bootstrap), "Idempotency-Key": crypto.randomUUID() });
  if (register.status < 200 || register.status >= 300) {
    throw new Error(`register ${username} failed: ${register.status} ${register.body}`);
  }
  const authed = { cookies: joinCookies(bootstrap.cookies, register.cookie), csrf: bootstrap.csrf };
  // The onboarding command is asynchronously accepted (202 + command receipt).
  const team = await httpRequest("POST", "/api/v1/onboarding/team",
      { name: `M11 Load Team ${suffix}`.slice(0, 96) },
      { ...csrfHeaders(authed), "Idempotency-Key": crypto.randomUUID() });
  if (team.status < 200 || team.status >= 300) {
    throw new Error(`onboarding for ${username} failed: ${team.status} ${team.body}`);
  }
  return { username, password };
}

/** Logs a cached principal in and returns the cookie header plus its Team coordinates. */
async function loginPrincipal({ username, password }) {
  // The stack's login defense rate-limits bursts from one network (the gate stack's
  // clients all share the Compose bridge address), so retries back off and callers
  // space logins out; the 12h session TTL then makes one login serve every drill.
  for (let attempt = 0; ; attempt++) {
    const bootstrap = await anonymousSession();
    const login = await httpRequest("POST", "/api/v1/auth/login",
        { identifier: username, password }, csrfHeaders(bootstrap));
    if (login.status === 429 && attempt < 5) {
      console.log(`login ${username} rate-limited; backing off (${3 * 2 ** attempt}s)`);
      await sleep(3000 * 2 ** attempt);
      continue;
    }
    if (login.status !== 200) {
      throw new Error(`login ${username} failed: ${login.status} ${login.body}`);
    }
    const cookies = joinCookies(bootstrap.cookies, login.cookie);
    // The onboarding command commits asynchronously; a freshly created account may need a
    // moment before its Team shows up in the session facts.
    for (let factsAttempt = 0; ; factsAttempt++) {
      const facts = await httpRequest("GET", "/api/v1/auth/session",
          undefined, { Cookie: cookies });
      const body = JSON.parse(facts.body);
      if (body.authenticated && body.teams?.length) {
        return {
          cookie: cookies,
          organizationId: body.principal.organizationId,
          teamId: body.teams[0].teamId,
        };
      }
      if (factsAttempt >= 5) {
        throw new Error(`session facts for ${username}: ${facts.body}`);
      }
      await sleep(2000);
    }
  }
}

function joinCookies(left, right) {
  return [left, right].filter(Boolean).join("; ");
}

/** Reuses a cached session when it still authenticates; returns null otherwise. */
async function cachedPrincipal(credential) {
  if (!credential.cookie || !credential.organizationId || !credential.teamId) return null;
  const facts = await httpRequest("GET", "/api/v1/auth/session",
      undefined, { Cookie: credential.cookie });
  if (facts.status !== 200) return null;
  const body = JSON.parse(facts.body);
  if (!body.authenticated || !body.teams?.length) return null;
  return {
    cookie: credential.cookie,
    organizationId: credential.organizationId,
    teamId: credential.teamId,
  };
}

/**
 * Ensures at least `n` cached principals exist, each with a live session. Credentials and
 * session cookies are plain account material for a disposable gate stack — the same
 * class of secret the e2e specs type into public registration forms, kept out of the
 * repository via var/. Sessions are validated and re-established strictly serially so
 * the per-network login defense never sees a burst from this generator.
 */
async function ensurePrincipals(n, runTag) {
  let credentials = [];
  if (existsSync(PRINCIPALS_FILE)) {
    credentials = JSON.parse(readFileSync(PRINCIPALS_FILE, "utf8"));
  }
  const missing = n - credentials.length;
  for (let i = 0; i < missing; i++) {
    credentials.push(await createPrincipal(credentials.length + 1, runTag));
    await sleep(1500);
  }
  const principals = [];
  let refreshed = false;
  for (const credential of credentials.slice(0, n)) {
    let principal = await cachedPrincipal(credential);
    if (!principal) {
      principal = await loginPrincipal(credential);
      credential.cookie = principal.cookie;
      credential.organizationId = principal.organizationId;
      credential.teamId = principal.teamId;
      refreshed = true;
      await sleep(2000);
    }
    principals.push(principal);
  }
  if (missing > 0 || refreshed) {
    mkdirSync(dirname(PRINCIPALS_FILE), { recursive: true });
    writeFileSync(PRINCIPALS_FILE, JSON.stringify(credentials, null, 2));
  }
  return principals;
}

// ------------------------------------------------------------- minimal ws client

class MiniWs {
  static connect(headers = {}) {
    return new Promise((resolve, reject) => {
      const key = crypto.randomBytes(16).toString("base64");
      const req = http.request({ host: HOST, port: PORT, path: WS_PATH, headers: {
        Origin: ORIGIN,
        Connection: "Upgrade", Upgrade: "websocket",
        "Sec-WebSocket-Version": "13", "Sec-WebSocket-Key": key, ...headers } });
      req.on("upgrade", (res, socket, head) => {
        const ws = new MiniWs(socket);
        if (head && head.length) ws.#feed(head);
        resolve(ws);
      });
      req.on("response", res => reject(Object.assign(
          new Error(`non-101 response: ${res.statusCode}`), { statusCode: res.statusCode })));
      req.on("error", reject);
      req.end();
    });
  }

  #pending = [];

  constructor(socket) {
    this.socket = socket;
    this.closed = false;
    this.closeCode = null;
    this.buffer = Buffer.alloc(0);
    this.onframe = null;
    socket.on("data", data => this.#feed(data));
    socket.on("close", () => { this.closed = true; this.#dispatch({ opcode: 0x8, payload: Buffer.alloc(0) }); });
    socket.on("error", () => { });
  }

  #feed(data) {
    this.buffer = Buffer.concat([this.buffer, data]);
    for (let frame = this.#parse(); frame; frame = this.#parse()) {
      if (frame.opcode === 0x8) {
        this.closeCode = frame.payload.length >= 2 ? frame.payload.readUInt16BE(0) : null;
        this.closed = true;
        try { this.socket.end(); } catch { }
      } else if (frame.opcode === 0x9) {
        this.#send(0x0a, frame.payload);
      }
      this.#dispatch(frame);
    }
  }

  #dispatch(frame) {
    if (this.onframe) { this.onframe(frame); return; }
    if (frame.opcode === 1) this.#pending.push(frame);
  }

  #parse() {
    const b = this.buffer;
    if (b.length < 2) return null;
    const opcode = b[0] & 0x0f;
    let len = b[1] & 0x7f;
    let off = 2;
    if (len === 126) { if (b.length < 4) return null; len = b.readUInt16BE(2); off = 4; }
    else if (len === 127) { if (b.length < 10) return null; len = Number(b.readBigUInt64BE(2)); off = 10; }
    if (b.length < off + len) return null;
    const payload = b.subarray(off, off + len);
    this.buffer = b.subarray(off + len);
    return { opcode, payload };
  }

  #send(opcode, payload) {
    const mask = crypto.randomBytes(4);
    let header;
    if (payload.length < 126) header = Buffer.from([0x80 | opcode, 0x80 | payload.length]);
    else if (payload.length < 65536) {
      // Two-byte extended length: 0x80|opcode + 0x80|126 + uint16 needs four bytes. (The
      // S01b probe never crossed 126 bytes per frame, so its copy of this branch had a
      // three-byte header that only product-sized UUID scopes expose.)
      header = Buffer.alloc(4);
      header[0] = 0x80 | opcode; header[1] = 0x80 | 126; header.writeUInt16BE(payload.length, 2);
    } else {
      header = Buffer.alloc(9);
      header[0] = 0x80 | opcode; header[1] = 0x80 | 127; header.writeBigUInt64BE(BigInt(payload.length), 2);
    }
    const masked = Buffer.alloc(payload.length);
    for (let i = 0; i < payload.length; i++) masked[i] = payload[i] ^ mask[i % 4];
    this.socket.write(Buffer.concat([header, mask, masked]));
  }

  /** Waits for the next inbound JSON text frame matching predicate. */
  waitJson(predicate = () => true, timeoutMs = 30000) {
    const i = this.#pending.findIndex(f => f.opcode === 1
        && predicate(safeJson(f.payload.toString("utf8"))));
    if (i >= 0) {
      const [match] = this.#pending.splice(i, 1);
      return Promise.resolve(safeJson(match.payload.toString("utf8")));
    }
    return new Promise((resolve, reject) => {
      const probe = frame => {
        if (frame.opcode === 1 && predicate(safeJson(frame.payload.toString("utf8")))) {
          this.onframe = null;
          clearTimeout(timer);
          resolve(safeJson(frame.payload.toString("utf8")));
          return true;
        }
        return false;
      };
      const timer = setTimeout(() => { this.onframe = null; reject(new Error("frame wait timeout")); }, timeoutMs);
      this.onframe = frame => { if (this.closed) { clearTimeout(timer); reject(new Error("closed while waiting")); return; } probe(frame); };
    });
  }

  sendJson(value) {
    this.#send(0x1, Buffer.from(JSON.stringify(value), "utf8"));
  }

  /** True backpressure drill: stop reading while writes keep flowing. */
  pause() { this.socket.pause(); }

  resume() { this.socket.resume(); }

  destroy() { this.socket.destroy(); }

  close() {
    if (!this.closed) this.#send(0x8, Buffer.alloc(0));
    try { this.socket.end(); } catch { }
  }
}

function safeJson(text) {
  try { return JSON.parse(text); } catch { return { type: "unparseable" }; }
}

async function openAndSubscribe(principal) {
  const ws = await MiniWs.connect({ Cookie: principal.cookie });
  await ws.waitJson(f => f.type === "welcome");
  const scope = { organization: principal.organizationId, team: principal.teamId };
  ws.sendJson({ type: "subscribe", scope });
  await ws.waitJson(f => f.type === "subscribed");
  return ws;
}

/** Hands out `count` connections round-robin over the principals, respecting the cap. */
function assignPrincipals(principals, count) {
  if (count > principals.length * MAX_CONNECTIONS_PER_PRINCIPAL) {
    throw new Error(`need ${count} connections but only ${principals.length} principals `
        + `x ${MAX_CONNECTIONS_PER_PRINCIPAL} = ${principals.length * MAX_CONNECTIONS_PER_PRINCIPAL}`);
  }
  return Array.from({ length: count }, (_, i) => principals[i % principals.length]);
}

/** Resolves with performance.now() when the socket closes, null on timeout. */
function watchClosed(ws, timeoutMs = 30000) {
  return new Promise(resolve => {
    if (ws.closed) { resolve(performance.now()); return; }
    const timer = setInterval(() => {
      if (ws.closed) { clearInterval(timer); clearTimeout(guard); resolve(performance.now()); }
    }, 5);
    const guard = setTimeout(() => { clearInterval(timer); resolve(null); }, timeoutMs);
  });
}

async function dockerStats(container) {
  const { stdout } = await execFileAsync("docker",
      ["stats", "--no-stream", "--format", "{{.CPUPerc}} {{.MemUsage}}", container]);
  return stdout.trim();
}

async function redis(...redisArgs) {
  const { stdout } = await execFileAsync("docker", ["exec", REDIS_CONTAINER, "redis-cli", ...redisArgs]);
  return stdout.trim();
}

/** Cursor-complete SCAN over the collaboration presence keyspace. */
async function presenceKeyCount() {
  const script = String.raw`
set -eu
cursor=0
total=0
keys=""
while :; do
  out=$(redis-cli SCAN "$cursor" MATCH 'crewscope:*:collaboration:v1:presence:*' COUNT 500)
  cursor=$(printf '%s\n' "$out" | head -n 1)
  found=$(printf '%s\n' "$out" | tail -n +2 | grep -c . || true)
  total=$((total + found))
  keys="$keys$(printf '%s\n' "$out" | tail -n +2)"
  if [ "$cursor" = "0" ]; then break; fi
done
echo "$total"
[ "$total" = "0" ] || { printf '%s\n' "$keys" | head -n 20; exit 3; }
`;
  // exit 3 is the script's own "survivors found" verdict (key list on stdout); the
  // promisified execFile rejects on any non-zero exit, so catch recovers both shapes.
  let stdout = "";
  let stderr = "";
  try {
    ({ stdout, stderr } = await execFileAsync(
        "docker", ["exec", REDIS_CONTAINER, "sh", "-c", script]));
  } catch (failure) {
    stdout = failure.stdout ?? "";
    stderr = failure.stderr ?? "";
  }
  const total = Number(stdout.trim().split("\n")[0]);
  if (!Number.isFinite(total)) {
    throw new Error(`presence SCAN failed: ${stderr || stdout}`);
  }
  return { total, survivors: stdout.trim().split("\n").slice(1).filter(Boolean) };
}

// ------------------------------------------------------------------ scenarios

async function storm() {
  console.log(`\n== storm: ${COUNT} concurrent handshakes over Nginx ==`);
  const principals = await ensurePrincipals(Math.ceil(COUNT / MAX_CONNECTIONS_PER_PRINCIPAL), `storm-${Date.now()}`);
  const assignments = assignPrincipals(principals, COUNT);
  const timings = [];
  const sockets = [];
  let failures = 0;
  await Promise.all(assignments.map(async principal => {
    const t0 = performance.now();
    try {
      const ws = await openAndSubscribe(principal);
      timings.push(performance.now() - t0);
      sockets.push(ws);
    } catch (failure) {
      failures += 1;
      console.error(`handshake failed: ${failure.message ?? failure}`);
    }
  }));
  reportTimings("handshake (upgrade -> subscribed)", timings);
  console.log(`failures: ${failures}/${COUNT} (expected 0)`);
  for (const ws of sockets) ws.close();
  await sleep(1500);
}

async function steady() {
  const n = Math.min(COUNT, 200);
  console.log(`\n== steady: ${n} connections under heartbeat for ${DURATION / 1000}s ==`);
  const principals = await ensurePrincipals(Math.ceil(n / MAX_CONNECTIONS_PER_PRINCIPAL), `steady-${Date.now()}`);
  const assignments = assignPrincipals(principals, n);
  const sockets = await Promise.all(assignments.map(p => openAndSubscribe(p)));
  // Live clients answer the server's application-level heartbeat with pongs.
  for (const ws of sockets) {
    ws.onframe = frame => {
      if (frame.opcode !== 1) return;
      const f = safeJson(frame.payload.toString("utf8"));
      if (f.type === "ping") ws.sendJson({ type: "pong" });
    };
  }
  const cpuBefore = await dockerStats(API_CONTAINER);
  console.log(`api container before: ${cpuBefore}`);
  const t0 = Date.now();
  while (Date.now() - t0 < DURATION) await sleep(2000);
  const cpuAfter = await dockerStats(API_CONTAINER);
  console.log(`api container after: ${cpuAfter}`);
  // The product protocol has no client ping, so the steady-state latency probe rides
  // idempotent re-subscribes (each round-trip re-runs authorization, deliberately —
  // that is the per-frame work a real subscribe costs).
  const probe = sockets[0];
  const scope = { organization: assignments[0].organizationId, team: assignments[0].teamId };
  const rtts = [];
  for (let i = 0; i < 20; i++) {
    const t = performance.now();
    probe.sendJson({ type: "subscribe", scope });
    await probe.waitJson(f => f.type === "subscribed");
    rtts.push(performance.now() - t);
    await sleep(500);
  }
  reportTimings("re-subscribe round-trip", rtts);
  for (const ws of sockets) ws.close();
  await sleep(1500);
}

async function reconnect() {
  console.log(`\n== reconnect: ${COUNT} connections dropped, then all reconnect at once ==`);
  const principals = await ensurePrincipals(Math.ceil(COUNT / MAX_CONNECTIONS_PER_PRINCIPAL), `recon-${Date.now()}`);
  const assignments = assignPrincipals(principals, COUNT);
  const first = await Promise.allSettled(assignments.map(p => openAndSubscribe(p)));
  const established = first.filter(r => r.status === "fulfilled").length;
  for (const r of first) if (r.status === "fulfilled") r.value.destroy();
  await sleep(2000);
  const timings = [];
  let failures = 0;
  await Promise.all(assignments.map(async principal => {
    const t0 = performance.now();
    try {
      const ws = await openAndSubscribe(principal);
      timings.push(performance.now() - t0);
      ws.close();
    } catch (failure) {
      failures += 1;
    }
  }));
  console.log(`first wave: ${established}/${COUNT}; reconnect wave: ${timings.length}/${COUNT}`);
  reportTimings("reconnect (upgrade -> subscribed)", timings);
  console.log(`reconnect failures: ${failures} (expected 0)`);
  await sleep(1500);
}

async function slow1013() {
  console.log("\n== slow1013: stalled reader with keep-alive pongs vs the outbound budget ==");
  const [principal] = await ensurePrincipals(1, `slow-${Date.now()}`);
  const victim = await openAndSubscribe(principal);
  const healthy = await openAndSubscribe(principal);
  // The healthy witness must stay a live client across the whole window — without pongs
  // it would be closed by the 30s inbound-idle bound and prove nothing about isolation.
  healthy.onframe = frame => {
    if (frame.opcode !== 1) return;
    const f = safeJson(frame.payload.toString("utf8"));
    if (f.type === "ping") healthy.sendJson({ type: "pong" });
  };
  // The victim stops READING (true TCP backpressure: kernel buffers fill, Nginx stops
  // relaying, the server's sends stall) but keeps WRITING blind pongs, so the inbound
  // idle timeout never fires and the only escape left is the outbound frame budget
  // closing the connection with 1013. This is the I01a obligation loopback could not
  // exercise; the gate stack runs heartbeat-interval=1s so the 256-frame budget lands
  // within minutes if the send path propagates backpressure at all.
  const t0 = performance.now();
  const blinder = setInterval(() => {
    if (!victim.closed) victim.sendJson({ type: "pong" });
  }, 3000);
  victim.pause();
  const closedAt = await watchClosed(victim, 420000);
  clearInterval(blinder);
  // The close frame is in the stalled read stream; draining it after the socket died is
  // best-effort, so the close code may stay unobservable from a fully paused reader.
  victim.resume();
  await sleep(500);
  const healthyRtt = await (async () => {
    const t = performance.now();
    healthy.sendJson({ type: "subscribe",
      scope: { organization: principal.organizationId, team: principal.teamId } });
    const answer = await healthy.waitJson(f => f.type === "subscribed", 10000).catch(() => null);
    return answer ? (performance.now() - t).toFixed(0) : null;
  })();
  console.log(closedAt === null
    ? "stalled reader NOT closed within 420s — outbound budget not reached on this path "
        + "(matches the S01b loopback finding; recorded as the honest result)"
    : `stalled reader closed after ${((closedAt - t0) / 1000).toFixed(0)}s `
        + `(close code ${victim.closeCode ?? "unobservable from a paused reader"}, expected 1013)`);
  console.log(healthyRtt !== null
    ? `healthy subscriber still served (re-subscribe rtt ${healthyRtt}ms — isolation holds)`
    : "FAIL: healthy subscriber stalled too — no isolation");
  healthy.close();
  await sleep(500);
}

async function leak() {
  console.log("\n== leak: nothing survives full disconnect in the presence keyspace ==");
  const n = 10;
  const [principal] = await ensurePrincipals(1, `leak-${Date.now()}`);
  const sockets = [];
  for (let i = 0; i < n; i++) sockets.push(await openAndSubscribe(principal));
  const before = await presenceKeyCount();
  console.log(`with ${n} subscribed connections: ${before.total} presence keys (expected > 0)`);
  for (const ws of sockets) ws.close();
  // Hard-reset half of a second wave so both teardown paths (WS close handshake and
  // transport-level RST) are exercised.
  const rst = [];
  for (let i = 0; i < 5; i++) rst.push(await openAndSubscribe(principal));
  for (const ws of rst) ws.destroy();
  // The assertion is "eventually zero", with the bound set by the ADR-032 lossy-presence
  // contract rather than by the teardown path alone: an in-flight heartbeat refresh can
  // land after the disconnect cleanup and re-add a ZSET member whose score rides a fresh
  // 45s TTL — the sweeper (60s cycle) then evicts it once the score expires, so a bare
  // key may legitimately survive ~TTL + sweep-interval after the last disconnect. Reads
  // never see it (the listPresent path lazy-evicts expired scores first).
  const deadline = Date.now() + 180000;
  let after = null;
  while (Date.now() < deadline) {
    after = await presenceKeyCount();
    if (after.total === 0) break;
    await sleep(3000);
  }
  console.log(`after full disconnect: ${after.total} presence keys (expected 0)`);
  if (after.total !== 0) {
    console.log(`FAIL: survivors: ${after.survivors.join(", ")}`);
    process.exitCode = 1;
  }
}

// ---------------------------------------------------------------------- main

const runners = { storm, steady, reconnect, slow1013, leak };
const run = runners[SCENARIO];
if (!run) { console.log(`unknown scenario: ${SCENARIO}`); process.exit(2); }

console.log(`M11-I01c product load generator: scenario=${SCENARIO} count=${COUNT} `
    + `duration=${DURATION / 1000}s base=${ORIGIN}`);
await run();
console.log("\ndone.");
process.exit(process.exitCode ?? 0);
