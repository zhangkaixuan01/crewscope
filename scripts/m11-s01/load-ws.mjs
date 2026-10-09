#!/usr/bin/env node
// M11-S01 WebSocket probe load generator (S01b evidence).
//
// Node's global WebSocket (undici) cannot attach a Cookie header, and the
// slow-client scenario needs direct socket control, so this implements the
// minimal RFC 6455 client on a raw upgraded socket: masked text frames out,
// unmasked frames in, close-code capture.
//
// Prerequisites: local Redis on 6379 (docker compose up -d redis), the probe
// built and running on 18095, redis-cli on PATH for the ttl scenario.
// Requires node >= 18.
//
// Usage: node load-ws.mjs [--scenario all|storm|steady|slow|fanout|revoke|ttl|multitab|crossteam]
//                         [--count 200] [--duration 120]

import http from "node:http";
import crypto from "node:crypto";
import { execFile } from "node:child_process";
import { promisify } from "node:util";

const HOST = "127.0.0.1";
const PORT = 18095;
const ORG = "org-load";
const PRESENCE_PREFIX = "crewscope:probe:m11s01:collaboration:v1:presence";

const execFileAsync = promisify(execFile);

const args = process.argv.slice(2);
function argOf(name, fallback) {
  const i = args.indexOf(`--${name}`);
  return i >= 0 && i + 1 < args.length ? args[i + 1] : fallback;
}
const SCENARIO = argOf("scenario", "all");
const COUNT = Number(argOf("count", 200));
const DURATION = Number(argOf("duration", 120)) * 1000;

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

async function login(teamId, principalId) {
  const res = await httpRequest("POST", "/probe/login",
      { organizationId: ORG, teamId, principalId, displayName: `Member ${principalId}` });
  if (res.status !== 200) throw new Error(`login failed: ${res.status} ${res.body}`);
  return res.cookie;
}

// Redis runs in the local docker compose stack; there is no host redis-cli.
const REDIS_CONTAINER = "crewscope-java-redis-1";
async function redis(...args) {
  const { stdout } = await execFileAsync("docker",
      ["exec", REDIS_CONTAINER, "redis-cli", ...args]);
  return stdout.trim();
}

function sleep(ms) { return new Promise(resolve => setTimeout(resolve, ms)); }

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

class MiniWs {
  static connect(path, headers = {}) {
    return new Promise((resolve, reject) => {
      const key = crypto.randomBytes(16).toString("base64");
      const req = http.request({ host: HOST, port: PORT, path, headers: {
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
      header = Buffer.alloc(3);
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

  pause() { this.socket.pause(); }

  destroy() { this.socket.destroy(); }

  close() {
    if (!this.closed) this.#send(0x8, Buffer.alloc(0));
    try { this.socket.end(); } catch { }
  }
}

function safeJson(text) {
  try { return JSON.parse(text); } catch { return { type: "unparseable" }; }
}

async function openAndSubscribe(cookie, scope) {
  const ws = await MiniWs.connect("/ws/probe", { Cookie: cookie });
  await ws.waitJson(f => f.type === "welcome");
  if (scope) {
    ws.sendJson({ type: "subscribe", scope });
    await ws.waitJson(f => f.type === "subscribed");
  }
  return ws;
}

// One session up front so the admin endpoints (stats/notify/revoke) accept us.
const ADMIN = await login("team-admin", "admin");

async function stats() {
  const res = await httpRequest("GET", "/probe/admin/stats", undefined, { Cookie: ADMIN });
  if (res.status !== 200) throw new Error(`stats failed: ${res.status} ${res.body}`);
  return JSON.parse(res.body);
}

// ------------------------------------------------------------------ scenarios

async function storm() {
  console.log(`\n== storm: ${COUNT} concurrent handshakes ==`);
  const cookies = [];
  for (let i = 0; i < COUNT; i++) cookies.push(await login("team-storm", `storm-${i}`));
  const before = await stats();
  const timings = [];
  const sockets = [];
  await Promise.all(cookies.map(async cookie => {
    const t0 = performance.now();
    const ws = await MiniWs.connect("/ws/probe", { Cookie: cookie });
    await ws.waitJson(f => f.type === "welcome", 60000);
    timings.push(performance.now() - t0);
    sockets.push(ws);
  }));
  const after = await stats();
  reportTimings("handshake (upgrade -> welcome)", timings);
  console.log(`connections=${after.connections} (expected ${COUNT}) `
      + `heapUsed ${before.heapUsedMb}MB -> ${after.heapUsedMb}MB `
      + `(~${((after.heapUsedMb - before.heapUsedMb) / COUNT * 1024).toFixed(0)}KB/conn)`);
  for (const ws of sockets) ws.close();
  await sleep(1500);
}

async function steady() {
  const n = Math.min(COUNT, 200);
  console.log(`\n== steady: ${n} connections under heartbeat for ${DURATION / 1000}s ==`);
  const cookies = [];
  for (let i = 0; i < n; i++) cookies.push(await login("team-steady", `steady-${i}`));
  const sockets = await Promise.all(cookies.map(c => MiniWs.connect("/ws/probe", { Cookie: c })));
  // Simulated live clients: the server heartbeat is a text ping frame, and a
  // client that never answers it is closed by the 30s inbound-idle bound —
  // so every load connection answers pings with application pongs.
  for (const ws of sockets) {
    ws.onframe = frame => {
      if (frame.opcode !== 1) return;
      const f = safeJson(frame.payload.toString("utf8"));
      if (f.type === "ping") ws.sendJson({ type: "pong" });
    };
  }
  const probeWs = sockets[0];
  const start = await stats();
  const t0 = Date.now();
  const interval = setInterval(() => {
    if (!probeWs.closed) probeWs.sendJson({ type: "ping" });
  }, 15000);
  while (Date.now() - t0 < DURATION) await sleep(2000);
  clearInterval(interval);
  const end = await stats();
  const rtts = [];
  for (let i = 0; i < 20; i++) {
    const t = performance.now();
    probeWs.sendJson({ type: "ping" });
    await probeWs.waitJson(f => f.type === "pong");
    rtts.push(performance.now() - t);
    await sleep(500);
  }
  reportTimings("ping->pong rtt", rtts);
  console.log(`steady window: connections=${end.connections} `
      + `heapUsed ${start.heapUsedMb}MB -> ${end.heapUsedMb}MB `
      + `cpu +${(end.totalCpuSeconds - start.totalCpuSeconds).toFixed(1)}s over ${DURATION / 1000}s `
      + `(framesIn=${end.framesIn - start.framesIn}, framesOut=${end.framesOut - start.framesOut})`);
  for (const ws of sockets) ws.close();
  await sleep(1500);
}

async function slow() {
  console.log("\n== slow: stalled client is dropped by the idle bound; others unaffected ==");
  const cookie = await login("team-slow", "slow-1");
  const victim = await openAndSubscribe(cookie,
      { team: "team-slow", resourceType: "WORK_ITEM", resourceId: "WI-SLOW" });
  // A healthy second subscriber proves isolation while the victim stalls.
  const healthy = await openAndSubscribe(cookie,
      { team: "team-slow", resourceType: "WORK_ITEM", resourceId: "WI-SLOW" });
  const t0 = performance.now();
  // Loopback note (recorded in presence-ttl.md): macOS autotunes the socket
  // buffers into the megabytes and the Reactor Netty WebSocket send path does
  // not gate its upstream requests on channel writability, so no in-process
  // burst can demonstrate sink-overflow here — on a real network the
  // bandwidth-delay product stalls the channel and the bounded sink (64)
  // closes the client with 1013. What the probe CAN show on loopback: a
  // client that stops sending (paused readers cannot even observe the close
  // frame locally) is still cut off by the 30s inbound-idle bound, and the
  // server keeps serving everyone else.
  // The victim stays readable but completely silent after subscribing.
  const burst = await httpRequest("POST", "/probe/admin/notify",
      { organizationId: ORG, teamId: "team-slow", resourceType: "WORK_ITEM",
        resourceId: "WI-SLOW", version: 1, count: 200 }, { Cookie: ADMIN });
  console.log(`burst of 200 changed frames while one reader is stalled: HTTP ${burst.status}`);
  const t1 = performance.now();
  healthy.sendJson({ type: "ping" });
  const pong = await healthy.waitJson(f => f.type === "pong", 10000).catch(() => null);
  console.log(pong ? "healthy subscriber still served (pong round-trip ok)" :
      "FAIL: healthy subscriber stalled too — no isolation");
  const closedAt = await watchClosed(victim, 60000);
  console.log(closedAt === null
      ? "FAIL: stalled client NOT closed within 60s — idle bound not enforced"
      : `stalled client closed after ${((closedAt - t0) / 1000).toFixed(0)}s `
          + `(close code ${victim.closeCode}, expected 1000 inbound-idle)`);
  healthy.close();
}

async function fanout() {
  console.log("\n== fanout: one changed notification to 50 subscribers ==");
  const subscribers = 50;
  const cookies = [];
  for (let i = 0; i < subscribers; i++) cookies.push(await login("team-fanout", `fanout-${i}`));
  const scope = { team: "team-fanout", resourceType: "WORK_ITEM", resourceId: "WI-F" };
  const sockets = await Promise.all(cookies.map(c => openAndSubscribe(c, scope)));
  const arrivals = new Map(); // subscriber index -> performance.now() of arrival
  sockets.forEach((ws, i) => {
    ws.onframe = frame => {
      if (frame.opcode !== 1) return;
      const f = safeJson(frame.payload.toString("utf8"));
      if (f.type === "changed" && f.version === 42 && !arrivals.has(i)) arrivals.set(i, performance.now());
    };
  });
  const t0 = performance.now();
  const res = await httpRequest("POST", "/probe/admin/notify",
      { organizationId: ORG, teamId: "team-fanout", resourceType: "WORK_ITEM",
        resourceId: "WI-F", version: 42, count: 1 }, { Cookie: ADMIN });
  const notifyMs = performance.now() - t0;
  await sleep(5000);
  const latencies = [...arrivals.values()].map(t => t - t0);
  console.log(`notify POST: HTTP ${res.status} in ${notifyMs.toFixed(1)}ms; `
      + `changed received by ${arrivals.size}/${subscribers} subscribers`);
  reportTimings("notify POST start -> changed arrival", latencies);
  for (const ws of sockets) ws.close();
  await sleep(1000);
}

async function revoke() {
  console.log("\n== revoke: layer1 (event path) and layer2 (revalidation path) ==");
  const admin = await login("team-revoke", "revoker");
  const victims = 2;
  const cookies = [];
  for (let i = 0; i < victims; i++) cookies.push(await login("team-revoke", `victim-${i}`));
  const sockets = [];
  for (const cookie of cookies) {
    sockets.push(await openAndSubscribe(cookie,
        { team: "team-revoke", resourceType: "WORK_ITEM", resourceId: "WI-R" }));
  }
  // layer 1: event-consumer path — close 4403 + presence removed immediately
  const t1 = performance.now();
  const res1 = await httpRequest("POST",
      `/probe/admin/teams/team-revoke/principals/victim-0/revoke?mode=layer1`,
      undefined, { Cookie: admin });
  const closedAt1 = await watchClosed(sockets[0], 15000);
  console.log(`layer1: HTTP ${res1.status} ${res1.body}`);
  console.log(closedAt1 === null
      ? "FAIL: layer1 did not close the connection within 15s"
      : `layer1: closed ${(closedAt1 - t1).toFixed(0)}ms after revoke `
          + `(close code ${sockets[0].closeCode}, expected 4403)`);
  // layer 2: revalidation path — flip membership, drive emits with client pings;
  // the 5s verdict cache bounds the window to cache + one emit
  const t2 = performance.now();
  const res2 = await httpRequest("POST",
      `/probe/admin/teams/team-revoke/principals/victim-1/revoke?mode=layer2`,
      undefined, { Cookie: admin });
  console.log(`layer2: HTTP ${res2.status} ${JSON.parse(res2.body).expectedEffect}`);
  const closedAt2 = await (async () => {
    const watcher = watchClosed(sockets[1], 30000);
    for (let i = 0; i < 30 && !sockets[1].closed; i++) {
      sockets[1].sendJson({ type: "ping" });
      await sleep(1000);
    }
    return watcher;
  })();
  console.log(closedAt2 === null
      ? "FAIL: layer2 did not close the connection within 30s of continuous pings"
      : `layer2: closed ${(closedAt2 - t2).toFixed(0)}ms after revoke `
          + `(close code ${sockets[1].closeCode}, expected 4403; bound = 5s cache + one emit)`);
  sockets[0].close();
}

async function ttl() {
  console.log("\n== ttl: clean close removes keys at once; orphaned keys expire at 45s ==");
  const scopeKey = `${PRESENCE_PREFIX}:scope:${ORG}:team-ttl:WORK_ITEM:WI-T`;
  // (a) clean close: register, verify keys exist, close, verify keys gone
  const cookie = await login("team-ttl", "ttl-1");
  const ws = await openAndSubscribe(cookie,
      { team: "team-ttl", resourceType: "WORK_ITEM", resourceId: "WI-T" });
  const zcardA = await redis("ZCARD", scopeKey);
  console.log(`(a) after subscribe: ZCARD scope=${zcardA} (expected 1)`);
  ws.close();
  await sleep(2000);
  const zcardAfterClose = await redis("ZCARD", scopeKey);
  console.log(`(a) after clean close: ZCARD scope=${zcardAfterClose} (expected 0 — remove path)`);
  // (b) hard reset (socket.destroy sends RST): server still cleans up via the
  // transport-level close, not the WS close handshake
  const ws2 = await openAndSubscribe(cookie,
      { team: "team-ttl", resourceType: "WORK_ITEM", resourceId: "WI-T" });
  ws2.destroy();
  await sleep(2000);
  const zcardAfterRst = await redis("ZCARD", scopeKey);
  console.log(`(b) after socket.destroy() (RST): ZCARD scope=${zcardAfterRst} (expected 0 — transport close path)`);
  // (c) orphan: seed a conn key + ZSET member bypassing the server entirely
  //     (simulates the partition/kill -9 case the local stack cannot produce),
  //     then watch the conn key expire at 45s and the ZSET member get evicted
  //     lazily by the next presence read
  const orphanId = "orphan-seed";
  await redis("HSET", `${PRESENCE_PREFIX}:conn:${orphanId}`,
      "principalId", "ttl-1", "displayName", "orphan",
      "resourceType", "WORK_ITEM", "resourceId", "WI-T");
  await redis("EXPIRE", `${PRESENCE_PREFIX}:conn:${orphanId}`, "45");
  await redis("ZADD", scopeKey, String(Date.now() + 45000), orphanId);
  console.log("(c) seeded orphan conn key (TTL 45s) + ZSET member; waiting 50s...");
  await sleep(50000);
  const orphanExists = await redis("EXISTS", `${PRESENCE_PREFIX}:conn:${orphanId}`);
  const zcardOrphan = await redis("ZCARD", scopeKey);
  console.log(`(c) after 50s: EXISTS conn key=${orphanExists} (expected 0 — TTL ceiling) `
      + `but ZCARD scope=${zcardOrphan} (stale member survives until a read)`);
  // lazy eviction: one live presence read sweeps the expired member
  const ws3 = await openAndSubscribe(cookie,
      { team: "team-ttl", resourceType: "WORK_ITEM", resourceId: "WI-T" });
  ws3.sendJson({ type: "presence", scope: { team: "team-ttl", resourceType: "WORK_ITEM", resourceId: "WI-T" } });
  await ws3.waitJson(f => f.type === "presence", 10000);
  const zcardSwept = await redis("ZCARD", scopeKey);
  console.log(`(c) after one presence read: ZCARD scope=${zcardSwept} `
      + `(expected 1 — ZREMRANGEBYSCORE lazy eviction; present view should list only ttl-1)`);
  ws3.close();
  await sleep(1000);
}

async function multitab() {
  console.log("\n== multitab: three connections on one session, presence dedups per principal ==");
  const cookie = await login("team-tabs", "tab-user");
  const scope = { team: "team-tabs", resourceType: "WORK_ITEM", resourceId: "WI-M" };
  const tabs = [];
  const connectionIds = [];
  for (let i = 0; i < 3; i++) {
    const ws = await MiniWs.connect("/ws/probe", { Cookie: cookie });
    const welcome = await ws.waitJson(f => f.type === "welcome");
    connectionIds.push(welcome.connectionId);
    ws.sendJson({ type: "subscribe", scope });
    await ws.waitJson(f => f.type === "subscribed");
    tabs.push(ws);
  }
  console.log(`connectionIds: ${connectionIds.join(", ")} (distinct: ${new Set(connectionIds).size}/3)`);
  tabs[0].sendJson({ type: "presence", scope });
  const answer = await tabs[0].waitJson(f => f.type === "presence");
  console.log(`presence view: ${JSON.stringify(answer.present)}`);
  const deduped = answer.present.length === 1 && answer.present[0].connections === 3;
  console.log(deduped
      ? "OK: one principal view aggregating 3 connections (per-principal dedup)"
      : "FAIL: presence not deduped per principal");
  for (const ws of tabs) ws.close();
  await sleep(1000);
}

async function crossteam() {
  console.log("\n== crossteam: cross-team subscribe is denied and counted ==");
  const cookieA = await login("team-a", "member-a");
  const ws = await openAndSubscribe(cookieA, null);
  ws.sendJson({ type: "subscribe", scope: { team: "team-b", resourceType: "WORK_ITEM", resourceId: "X" } });
  const answer = await ws.waitJson(f => f.type === "error" || f.type === "subscribed");
  console.log(answer.type === "error" && answer.code === "cross_team_denied"
      ? `OK: denied with ${answer.code}`
      : `FAIL: expected cross_team_denied, got ${JSON.stringify(answer)}`);
  ws.close();
  await sleep(500);
}

// ---------------------------------------------------------------------- main

const runners = { storm, steady, slow, fanout, revoke, ttl, multitab, crossteam };
const wanted = SCENARIO === "all"
    ? ["storm", "steady", "slow", "fanout", "revoke", "ttl", "multitab", "crossteam"]
    : [SCENARIO];

console.log(`M11-S01 load generator: scenario=${SCENARIO} count=${COUNT} duration=${DURATION / 1000}s`);
for (const name of wanted) {
  const run = runners[name];
  if (!run) { console.log(`unknown scenario: ${name}`); process.exit(2); }
  await run();
}
console.log("\ndone.");
process.exit(0);
