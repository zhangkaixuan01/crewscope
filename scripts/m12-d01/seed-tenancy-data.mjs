#!/usr/bin/env node
// M12-D01 tenancy drift drill: seeds a non-trivial ownership footprint through the public REST
// surface on a freshly started gate stack, then prints the organization/team/project coordinates
// as JSON for the gate script to consume.
//
// Flow: CSRF bootstrap via session body -> OPEN registration (auto session) -> first-team
// onboarding -> one work project (202 receipt, key-polled to convergence) -> one TASK work item.
// No real credentials, no humans, no model keys.

const baseUrl = process.argv[2] ?? "";
if (!baseUrl) {
  console.error("usage: node seed-tenancy-data.mjs <baseUrl> [suffix]");
  process.exit(2);
}
const suffix = process.argv[3] ?? String(Date.now());
const username = `d01-${suffix}`.replace(/[^a-z0-9-]/gi, "").slice(0, 48);
const password = `D01Drill-${suffix}-Pass!1`;
// WorkProjectKey.FORMAT_REGEX = [A-Z][A-Z0-9]{1,9}: "D01" + 6 stamp digits = 9 chars.
const projectKey = `D01${String(suffix).replace(/[^0-9]/g, "").slice(-6).padStart(6, "0")}`.slice(0, 10);

let cookies = new Map();
let csrf = { headerName: "x-xsrf-token", token: null };

function cookieHeader() {
  return [...cookies.entries()].map(([k, v]) => `${k}=${v}`).join("; ");
}

function absorb(response) {
  for (const line of response.headers.getSetCookie?.() ?? []) {
    const [pair] = line.split(";");
    const eq = pair.indexOf("=");
    if (eq > 0) cookies.set(pair.slice(0, eq).trim(), pair.slice(eq + 1).trim());
  }
}

async function call(method, path, body) {
  const headers = { "content-type": "application/json", accept: "application/json" };
  if (cookies.size) headers.cookie = cookieHeader();
  if (method !== "GET" && method !== "HEAD") headers["idempotency-key"] = crypto.randomUUID();
  const bodyToken = csrf.token;
  if (bodyToken) headers[csrf.headerName.toLowerCase()] = bodyToken;
  else if (cookies.get("XSRF-TOKEN")) headers["x-xsrf-token"] = decodeURIComponent(cookies.get("XSRF-TOKEN"));
  const response = await fetch(`${baseUrl}${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  absorb(response);
  const text = await response.text();
  let json = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    json = null;
  }
  if (!response.ok) {
    throw new Error(`${method} ${path} -> ${response.status}: ${text.slice(0, 300)}`);
  }
  return json;
}

function requireField(value, what, raw) {
  if (!value) throw new Error(`${what} missing: ${JSON.stringify(raw).slice(0, 300)}`);
  return value;
}

async function poll(predicate, what, attempts = 30, delayMs = 500) {
  for (let attempt = 0; attempt < attempts; attempt++) {
    const current = await predicate();
    if (current) return current;
    await new Promise((resolve) => setTimeout(resolve, delayMs));
  }
  throw new Error(`timed out waiting for ${what}`);
}

// CSRF bootstrap: the session body carries (headerName, token) for non-browser clients.
const bootstrap = await call("GET", "/api/v1/auth/session");
if (bootstrap?.csrf?.token) csrf = bootstrap.csrf;

await call("POST", "/api/v1/auth/register", {
  username,
  email: `${username}@example.test`,
  displayName: `D01 Drill ${suffix}`,
  password,
  invitationToken: null,
});

// Registration establishes the session; the principal view carries the organization coordinate.
const session = await call("GET", "/api/v1/auth/session");
const organizationId = requireField(
    session?.principal?.organizationId, "principal.organizationId", session);

await call("POST", "/api/v1/onboarding/team", { name: `D01 漂移演练队 ${suffix}` });

// Onboarding answers with state only; the team id arrives on the refreshed session view.
const afterTeam = await poll(async () => {
  const view = await call("GET", "/api/v1/auth/session");
  return (view?.teams ?? []).length > 0 ? view.teams[0].teamId : null;
}, "teams[0].teamId after onboarding");
const teamId = requireField(afterTeam, "teams[0].teamId", afterTeam);

await call(
    "POST",
    `/api/v1/organizations/${organizationId}/teams/${teamId}/work-projects`,
    { key: projectKey, name: "D01 Drift Project" });

// 202 receipt: the project projection converges asynchronously; poll the list by key.
const projectId = await poll(async () => {
  const page = await call(
      "GET", `/api/v1/organizations/${organizationId}/teams/${teamId}/work-projects?limit=100`);
  return (page?.items ?? []).find((item) => item.key === projectKey)?.id ?? null;
}, `work project ${projectKey} to appear`);
requireField(projectId, "projectId", projectId);

// 202 receipt: the gate's SQL wait loop confirms the work item row before snapshotting.
await call(
    "POST",
    `/api/v1/organizations/${organizationId}/teams/${teamId}/work-projects/${projectId}/work-items`,
    { type: "TASK", title: `D01 漂移探针 ${suffix}` });

console.log(JSON.stringify({ organizationId, teamId, projectId, projectKey, username }));
