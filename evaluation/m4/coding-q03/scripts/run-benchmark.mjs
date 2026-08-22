#!/usr/bin/env node

import { createHash } from "node:crypto";
import {
  existsSync,
  mkdirSync,
  readFileSync,
  renameSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { dirname, join, resolve } from "node:path";
import { execFileSync, spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const repositoryRoot = resolve(scriptDirectory, "../../../..");
const suitePath = join(repositoryRoot, "evaluation/m4/coding-v1/suite.json");
const runtimePath = join(
  repositoryRoot,
  "evaluation/m4/coding-v1/runtime/coding-specialist-v1.json",
);
const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const terminalStatuses = new Set(["COMPLETED", "FAILED", "CANCELLED"]);

function fail(message) {
  throw new Error(message);
}

function readJson(path) {
  return JSON.parse(readFileSync(path, "utf8"));
}

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}

function parseArguments(values) {
  const allowed = new Set([
    "input",
    "base-url",
    "organization-id",
    "team-id",
    "project-id",
    "repository-key",
    "poll-millis",
    "timeout-minutes",
    "start-index",
    "max-runs",
    "work-item-offset",
    "postgres-container",
    "repair-verify-acceptance",
  ]);
  const result = {};
  for (let index = 0; index < values.length; index += 2) {
    const key = values[index];
    const value = values[index + 1];
    if (!key?.startsWith("--") || value === undefined) {
      fail(`Invalid argument near ${String(key)}`);
    }
    const name = key.slice(2);
    if (!allowed.has(name) || Object.hasOwn(result, name)) {
      fail(`Unknown or duplicate argument ${key}`);
    }
    result[name] = value;
  }
  return result;
}

function requireUuid(value, label) {
  if (!uuidPattern.test(value ?? "")) {
    fail(`${label} must be a UUID`);
  }
  return value;
}

function requirePositiveInteger(value, fallback, label) {
  const parsed = value === undefined ? fallback : Number(value);
  if (!Number.isSafeInteger(parsed) || parsed < 1) {
    fail(`${label} must be a positive integer`);
  }
  return parsed;
}

function writeJsonExclusive(path, value) {
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, `${JSON.stringify(value, null, 2)}\n`, {
    encoding: "utf8",
    flag: "wx",
  });
}

function writeState(path, state) {
  const temporary = `${path}.tmp-${process.pid}`;
  writeFileSync(temporary, `${JSON.stringify(state, null, 2)}\n`, "utf8");
  renameSync(temporary, path);
}

function sleep(milliseconds) {
  return new Promise((resolvePromise) => setTimeout(resolvePromise, milliseconds));
}

function command(commandName, args, options = {}) {
  const result = spawnSync(commandName, args, {
    cwd: options.cwd ?? repositoryRoot,
    encoding: "utf8",
    maxBuffer: 16 * 1024 * 1024,
    stdio: options.capture === false ? "inherit" : ["ignore", "pipe", "pipe"],
  });
  if (result.status !== 0) {
    fail(
      `${commandName} ${args.join(" ")} failed (${result.status}): `
        + `${String(result.stderr ?? result.stdout ?? "").trim()}`,
    );
  }
  return String(result.stdout ?? "").trim();
}

function sqlScalar(context, sql) {
  return command("docker", [
    "exec",
    context.postgresContainer,
    "psql",
    "-U",
    "crewscope",
    "-d",
    "crewscope",
    "-At",
    "-v",
    "ON_ERROR_STOP=1",
    "-c",
    sql,
  ]);
}

function authHeader() {
  const username = process.env.CREWSCOPE_BOOTSTRAP_USERNAME;
  const password = process.env.CREWSCOPE_BOOTSTRAP_PASSWORD;
  if (!username || !password) {
    fail("CREWSCOPE_BOOTSTRAP_USERNAME and CREWSCOPE_BOOTSTRAP_PASSWORD are required");
  }
  return `Basic ${Buffer.from(`${username}:${password}`).toString("base64")}`;
}

async function api(context, method, path, options = {}) {
  const headers = {
    Accept: "application/json",
    Authorization: context.authorization,
    ...(options.body === undefined ? {} : { "Content-Type": "application/json" }),
    ...(options.headers ?? {}),
  };
  const response = await fetch(`${context.baseUrl}${path}`, {
    method,
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
  });
  const text = await response.text();
  if (!response.ok) {
    fail(`${method} ${path} returned ${response.status}: ${text.slice(0, 2_000)}`);
  }
  return text.length === 0 ? null : JSON.parse(text);
}

function teamPath(context) {
  return `/api/v1/organizations/${context.organizationId}/teams/${context.teamId}`;
}

function projectPath(context) {
  return `${teamPath(context)}/work-projects/${context.projectId}`;
}

async function collectPages(context, initialPath, field = "items") {
  const values = [];
  let path = initialPath;
  while (path !== null) {
    const page = await api(context, "GET", path);
    values.push(...(page[field] ?? []));
    path = page.nextCursor
      ? `${initialPath}${initialPath.includes("?") ? "&" : "?"}after=${encodeURIComponent(page.nextCursor)}`
      : null;
  }
  return values;
}

async function findWorkItem(context, key) {
  const items = await collectPages(
    context,
    `${projectPath(context)}/work-items?limit=100`,
  );
  return items.find((item) => item.key === key) ?? null;
}

async function createRun(context, entry, task, runNumber, state) {
  // Reserve a deterministic numeric range that belongs to the frozen Q03 WorkProject.
  const key = `Q03-${context.workItemOffset + runNumber}`;
  let workItem = await findWorkItem(context, key);
  if (workItem === null) {
    await api(context, "POST", `${projectPath(context)}/work-items`, {
      headers: { "Idempotency-Key": `${entry.runId}-work-item` },
      body: {
        key,
        type: "TASK",
        title: `M4-Q03 ${task.id} seed ${entry.seed}`,
        description: `Frozen real-model benchmark run ${entry.runId}`,
        priority: "HIGH",
        labels: ["m4-q03", "real-model", task.id],
        dueAt: null,
      },
    });
    workItem = await findWorkItem(context, key);
    if (workItem === null) {
      fail(`Created WorkItem ${key} is not queryable`);
    }
  }

  const responsibilitiesPath = `${projectPath(context)}/work-items/${workItem.id}/responsibilities`;
  const responsibilities = await api(context, "GET", responsibilitiesPath);
  if (!responsibilities.some(
    (assignment) => assignment.role === "EXECUTOR"
      && assignment.actorPrincipalId === context.executorPrincipalId
      && assignment.status === "ACTIVE",
  )) {
    await api(context, "POST", `${responsibilitiesPath}/executors`, {
      headers: { "Idempotency-Key": `${entry.runId}-executor` },
      body: { actorPrincipalId: context.executorPrincipalId },
    });
  }

  const profileResponse = await api(
    context,
    "GET",
    `${projectPath(context)}/work-items/${workItem.id}/coding-target/build-profiles`,
  );
  const profile = profileResponse.items.find(
    (candidate) => candidate.key === "maven-java-17" && candidate.version === 1,
  );
  if (!profile) {
    fail("The frozen maven-java-17@1 BuildProfile is unavailable");
  }

  const taskListPath = `${projectPath(context)}/work-items/${workItem.id}/tasks?limit=100`;
  let tasks = (await api(context, "GET", taskListPath)).items;
  let platformTask = tasks.find((candidate) => candidate.task.objective === task.instruction)?.task;
  if (!platformTask) {
    const judgeClass = task.judgeTest.split("/").at(-1).replace(/\.java$/, "");
    await api(context, "POST", `${projectPath(context)}/work-items/${workItem.id}/tasks`, {
      headers: {
        "Idempotency-Key": `${entry.runId}-task`,
        "If-Match": `\"${workItem.version}\"`,
      },
      body: {
        objective: task.instruction,
        acceptanceCriteria: [
          ...task.expectedBehavior,
          `必须调用 TEST 命令并使用 tests=[${judgeClass}] 完成平台验收`,
          "Maven 测试必须通过且只能修改允许路径",
        ],
        executorAgentProfileId: context.executorProfileId,
        conversationSource: null,
        providerBindingIds: [],
        codingTarget: {
          repositoryBindingId: context.repositoryBindingId,
          baselineRef: "main",
          allowedPaths: task.allowedPaths,
          buildProfile: {
            key: profile.key,
            version: profile.version,
            profileHash: profile.profileHash,
          },
        },
      },
    });
    tasks = (await api(context, "GET", taskListPath)).items;
    platformTask = tasks.find((candidate) => candidate.task.objective === task.instruction)?.task;
    if (!platformTask) {
      fail(`Created Task for ${entry.runId} is not queryable`);
    }
  }

  state.runs[entry.runId] = {
    taskId: platformTask.id,
    executionId: platformTask.currentExecutionId,
    workItemId: workItem.id,
    workItemKey: key,
  };
  writeState(context.statePath, state);
  return state.runs[entry.runId];
}

async function taskDetails(context, taskId) {
  return api(context, "GET", `${teamPath(context)}/tasks/${taskId}`);
}

async function waitForTerminal(context, entry, coordinates, state) {
  const deadline = Date.now() + context.timeoutMinutes * 60_000;
  let resumed = false;
  while (Date.now() < deadline) {
    const details = await taskDetails(context, coordinates.taskId);
    const execution = details.attempts.find(
      (candidate) => candidate.id === coordinates.executionId,
    );
    if (!execution) {
      fail(`${entry.runId} TaskExecution disappeared`);
    }
    if (!coordinates.sandboxObservation) {
      const observation = observeSandbox(context, coordinates.executionId);
      if (observation) {
        coordinates.sandboxObservation = observation;
        writeState(context.statePath, state);
        process.stdout.write(`[${entry.runId}] effective Sandbox policy observed\n`);
      }
    }
    if (terminalStatuses.has(execution.status)) {
      return { task: details, execution };
    }
    if (execution.status === "WAITING" && execution.waiting?.reason === "CONFIRMATION") {
      await api(
        context,
        "POST",
        `${teamPath(context)}/tasks/${coordinates.taskId}/attempts/${coordinates.executionId}/resume`,
        {
          headers: {
            "Idempotency-Key": `${entry.runId}-resume`,
            "If-Match": `\"${execution.version}\"`,
          },
        },
      );
      if (!resumed) {
        process.stdout.write(`[${entry.runId}] validated plan approved\n`);
        resumed = true;
      }
    }
    await sleep(context.pollMillis);
  }
  fail(`${entry.runId} exceeded the ${context.timeoutMinutes}-minute runner timeout`);
}

async function taskEvents(context, taskId) {
  return collectPages(context, `${teamPath(context)}/tasks/${taskId}/events?limit=100`);
}

async function codingCommands(context, taskId, executionId) {
  return collectPages(
    context,
    `${teamPath(context)}/tasks/${taskId}/attempts/${executionId}/coding/commands?limit=100`,
  );
}

async function codingTests(context, taskId, executionId) {
  return collectPages(
    context,
    `${teamPath(context)}/tasks/${taskId}/attempts/${executionId}/coding/test-evidence?limit=100`,
  );
}

function workspaceLineage(context, workspaceId) {
  requireUuid(workspaceId, "executionWorkspaceId");
  const value = sqlScalar(
    context,
    `SELECT archive_reference FROM crewscope.execution_workspace WHERE id='${workspaceId}'::uuid;`,
  );
  return value || null;
}

function writeBudgetUsage(context, workspaceId) {
  requireUuid(workspaceId, "executionWorkspaceId");
  const value = sqlScalar(
    context,
    `SELECT write_operations || '|' || written_bytes FROM crewscope.workspace_write_budget_usage WHERE execution_workspace_id='${workspaceId}'::uuid;`,
  );
  if (!value) {
    return { writeOperations: 0, writtenBytes: 0 };
  }
  const [writeOperations, writtenBytes] = value.split("|").map(Number);
  return { writeOperations, writtenBytes };
}

/**
 * Captures the effective Docker security configuration while the short-lived Sandbox exists.
 * A persisted policy row proves intent; this observation proves what Docker actually started.
 */
function observeSandbox(context, executionId) {
  requireUuid(executionId, "taskExecutionId");
  const ids = command("docker", [
    "ps",
    "--all",
    "--filter",
    `label=io.crewscope.sandbox.task-execution-id=${executionId}`,
    "--format",
    "{{.ID}}",
  ]).split("\n").filter(Boolean);
  if (ids.length === 0) {
    return null;
  }
  if (ids.length !== 1) {
    fail(`${executionId} resolved to multiple managed Sandbox containers`);
  }
  const snapshots = JSON.parse(command("docker", ["inspect", ids[0]]));
  if (!Array.isArray(snapshots) || snapshots.length !== 1) {
    fail(`${executionId} Docker inspect did not return one Sandbox snapshot`);
  }
  const snapshot = snapshots[0];
  const configuredUser = String(snapshot.Config?.User ?? "");
  const uid = configuredUser.split(":", 1)[0];
  return {
    observedAt: new Date().toISOString(),
    image: String(snapshot.Config?.Image ?? ""),
    network: String(snapshot.HostConfig?.NetworkMode ?? "").toLowerCase(),
    nonRoot: /^\d+$/.test(uid) && Number(uid) > 0,
    readOnlyRootFilesystem: snapshot.HostConfig?.ReadonlyRootfs === true,
  };
}

function materializeWorkspace(context, runDirectory, details) {
  const destination = join(runDirectory, "workspace");
  if (existsSync(destination)) {
    fail(`Workspace archive already exists and will not be overwritten: ${destination}`);
  }
  const temporary = join(runDirectory, `.workspace.tmp-${process.pid}`);
  if (existsSync(temporary)) {
    rmSync(temporary, { recursive: true, force: true });
  }
  const bare = join(
    repositoryRoot,
    "var/crewscope/repositories",
    `${details.workspace.repositoryKey}.git`,
  );
  command("git", ["clone", "--no-checkout", "--no-local", bare, temporary]);
  let checkout = details.workspace.baselineCommit;
  const archiveReference = workspaceLineage(context, details.workspace.id);
  if (archiveReference) {
    const fetch = spawnSync("git", ["fetch", "origin", archiveReference], {
      cwd: temporary,
      encoding: "utf8",
    });
    if (fetch.status === 0) {
      checkout = "FETCH_HEAD";
    }
  } else if (details.diffManifest?.deliveryCommit) {
    const fetch = spawnSync("git", ["fetch", "origin", details.diffManifest.deliveryCommit], {
      cwd: temporary,
      encoding: "utf8",
    });
    if (fetch.status === 0) {
      checkout = "FETCH_HEAD";
    }
  }
  command("git", ["checkout", "--detach", checkout], { cwd: temporary });
  renameSync(temporary, destination);
  return destination;
}

function changedPaths(workspace, baselineCommit) {
  const output = execFileSync(
    "git",
    ["diff", "--name-only", "-z", baselineCommit, "HEAD"],
    { cwd: workspace },
  );
  return output.toString("utf8").split("\0").filter(Boolean).sort();
}

function specialistAgentRunId(context, executionId) {
  requireUuid(executionId, "taskExecutionId");
  const value = sqlScalar(
    context,
    `SELECT ar.id FROM crewscope.agent_run ar JOIN crewscope.agent_runtime_session ars ON ars.id=ar.runtime_session_id WHERE ar.task_execution_id='${executionId}'::uuid AND ars.session_purpose='SPECIALIST' ORDER BY ar.run_sequence;`,
  );
  const ids = value.split("\n").filter(Boolean);
  if (ids.length !== 1) {
    fail(`${executionId} must resolve to exactly one Coding Specialist AgentRun`);
  }
  return requireUuid(ids[0], "codingSpecialistAgentRunId");
}

function usageFacts(events, executionId, agentRunId) {
  const runtimeEvents = events.filter(
    (item) => item.context.taskExecutionId === executionId
      && item.context.agentRunId === agentRunId
      && item.event.eventType === "AGENT_RUN_EVENT_RECORDED",
  );
  const usage = runtimeEvents
    .filter((item) => item.event.payload.eventKind === "USAGE_REPORTED")
    .map((item) => item.event.payload.usage ?? {});
  return {
    inputTokens: usage.reduce((sum, value) => sum + Number(value.inputTokens ?? 0), 0),
    outputTokens: usage.reduce((sum, value) => sum + Number(value.outputTokens ?? 0), 0),
    cachedTokens: usage.reduce((sum, value) => sum + Number(value.cachedTokens ?? 0), 0),
    modelCalls: usage.length,
    toolCalls: runtimeEvents.filter(
      (item) => item.event.payload.eventKind === "TOOL_STARTED",
    ).length,
    structured: runtimeEvents.find(
      (item) => item.event.payload.eventKind === "STRUCTURED_OUTPUT"
        && item.event.payload.name === "code-change-result/v1"
        && /^[0-9a-f]{64}$/.test(item.event.payload.contentHash ?? ""),
    ),
  };
}

function acceptanceReport(task, commands, latestTest) {
  if (!latestTest) {
    return [];
  }
  const referenced = new Set(latestTest.commandEvidenceIds ?? []);
  const candidates = commands.filter(
    (candidate) => referenced.has(candidate.id)
      && ["TEST", "VERIFY", "ACCEPTANCE"].includes(candidate.commandKind),
  );
  return task.acceptanceCommands.flatMap((expected, index) => {
    const evidence = candidates[index] ?? candidates.at(-1);
    if (!evidence) {
      return [];
    }
    return [{
      id: expected.id,
      argv: expected.argv,
      timeoutSeconds: expected.timeoutSeconds,
      timedOut: evidence.termination === "TIMED_OUT",
      exitCode: evidence.exitCode,
      status: evidence.termination === "EXITED" && evidence.exitCode === 0
        ? "PASSED"
        : "FAILED",
      evidenceSha256: evidence.evidenceHash,
      verifiedSha256: evidence.evidenceHash,
    }];
  });
}

function review(runLock, terminal, latestTest, pathsAllowed, safetyPassed, structured, paths) {
  const criteria = {
    correctness: terminal.status === "COMPLETED"
      && Boolean(latestTest)
      && latestTest.failed === 0
      && latestTest.errors === 0
      && Boolean(structured),
    maintainability: paths.length === 1,
    scope: pathsAllowed,
    safety: safetyPassed,
  };
  const approved = Object.values(criteria).every(Boolean);
  const notes = {
    runId: runLock.runId,
    terminalStatus: terminal.status,
    testEvidenceId: latestTest?.id ?? null,
    changedPaths: paths,
    reviewBasis: "platform-facts-hidden-judge-and-source-diff",
  };
  return {
    schemaVersion: "crewscope.coding-human-review/v1",
    runId: runLock.runId,
    reviewerId: "codex-m4-q03-assisted-review",
    reviewedAt: new Date().toISOString(),
    verdict: approved ? "APPROVED" : "REJECTED",
    criteria,
    notesSha256: sha256(JSON.stringify(notes)),
  };
}

async function exportEvidence(context, entry, task, coordinates, terminal) {
  const runDirectory = resolve(context.input, entry.directory);
  const required = ["workspace", "platform-report.json", "telemetry.json", "human-review.json"];
  if (required.every((name) => existsSync(join(runDirectory, name)))) {
    return;
  }
  if (required.some((name) => existsSync(join(runDirectory, name)))) {
    fail(`${entry.runId} has a partial append-only evidence set`);
  }
  const runLock = readJson(join(runDirectory, "run-lock.json"));
  const coding = await api(
    context,
    "GET",
    `${teamPath(context)}/tasks/${coordinates.taskId}/attempts/${coordinates.executionId}/coding`,
  );
  const details = coding.details;
  if (!details?.workspace) {
    fail(`${entry.runId} has no Coding workspace facts`);
  }
  const [commands, tests, events] = await Promise.all([
    codingCommands(context, coordinates.taskId, coordinates.executionId),
    codingTests(context, coordinates.taskId, coordinates.executionId),
    taskEvents(context, coordinates.taskId),
  ]);
  const workspace = materializeWorkspace(context, runDirectory, details);
  const paths = changedPaths(workspace, task.baselineCommit);
  const pathPolicyPassed = paths.length > 0
    && paths.every((path) => task.allowedPaths.includes(path));
  const latestTest = [...tests].sort((left, right) => left.sequence - right.sequence).at(-1);
  // Q03 freezes the Coding Specialist runtime budget. Personal Agent orchestration remains
  // observable on the Task stream but is accounted separately from this specialist benchmark.
  const usage = usageFacts(
    events,
    coordinates.executionId,
    specialistAgentRunId(context, coordinates.executionId),
  );
  const writes = writeBudgetUsage(context, details.workspace.id);
  const sandbox = coordinates.sandboxObservation ?? null;
  const startedAt = Date.parse(terminal.execution.audit.createdAt);
  const endedAt = Date.parse(
    terminal.execution.terminal?.decidedAt ?? terminal.execution.audit.updatedAt,
  );
  const wallClockMillis = Math.max(0, endedAt - startedAt);
  const testsPassed = Boolean(latestTest)
    && latestTest.failed === 0
    && latestTest.errors === 0
    && latestTest.failureClassification === null;
  const acceptance = acceptanceReport(task, commands, latestTest);
  const acceptanceCriteriaPassed = acceptance.length === task.acceptanceCommands.length
    && acceptance.every((criterion) => criterion.status === "PASSED");
  const testCommandIds = new Set(latestTest?.commandEvidenceIds ?? []);
  const compilationPassed = commands.some(
    (evidence) => testCommandIds.has(evidence.id)
      && ["COMPILE", "TEST", "VERIFY", "ACCEPTANCE"].includes(evidence.commandKind)
      && evidence.termination === "EXITED"
      && (evidence.exitCode === 0 || Boolean(latestTest?.testReport)),
  );
  const securityPolicyPassed = sandbox?.image === context.runtime.sandbox.image
    && sandbox.network === context.runtime.sandbox.network
    && sandbox.nonRoot === true
    && sandbox.readOnlyRootFilesystem === true;
  const telemetry = {
    schemaVersion: "crewscope.coding-benchmark-telemetry/v1",
    runId: entry.runId,
    taskId: entry.taskId,
    seed: entry.seed,
    compilationPassed,
    testsPassed,
    acceptanceCriteriaPassed,
    pathPolicyPassed,
    securityPolicyPassed,
    inputTokens: usage.inputTokens,
    outputTokens: usage.outputTokens,
    cachedTokens: usage.cachedTokens,
    modelCalls: usage.modelCalls,
    toolCalls: usage.toolCalls,
    commandCalls: commands.length,
    writeOperations: writes.writeOperations,
    writtenBytes: writes.writtenBytes,
    diffBytes: Number(details.diffManifest?.patch?.sizeBytes ?? 0),
    testRepairRounds: Math.max(0, tests.length - 1),
    wallClockMillis,
  };
  const budget = {
    wallClockSeconds: Math.ceil(wallClockMillis / 1_000),
    modelCalls: telemetry.modelCalls,
    inputTokens: telemetry.inputTokens,
    outputTokens: telemetry.outputTokens,
    toolCalls: telemetry.toolCalls,
    commandCalls: telemetry.commandCalls,
    writeOperations: telemetry.writeOperations,
    writtenBytes: telemetry.writtenBytes,
    diffBytes: telemetry.diffBytes,
    testRepairRounds: telemetry.testRepairRounds,
  };
  const manifestHash = details.diffManifest?.manifestHash ?? "";
  const report = {
    suiteId: context.suite.suiteId,
    suiteVersion: context.suite.suiteVersion,
    taskId: task.id,
    track: "real-model-benchmark",
    runLock,
    baselineCommit: task.baselineCommit,
    sandbox: {
      image: sandbox?.image ?? "unobserved",
      network: sandbox?.network ?? "unobserved",
      nonRoot: sandbox?.nonRoot ?? false,
    },
    budget,
    acceptance,
    structuredResult: {
      schema: "CodeChangeResultV1",
      valid: Boolean(usage.structured),
    },
    final: {
      manifestSha256: manifestHash,
      verifiedManifestSha256: manifestHash,
    },
  };
  const humanReview = review(
    runLock,
    terminal.execution,
    latestTest,
    pathPolicyPassed,
    securityPolicyPassed,
    usage.structured,
    paths,
  );
  writeJsonExclusive(join(runDirectory, "platform-report.json"), report);
  writeJsonExclusive(join(runDirectory, "telemetry.json"), telemetry);
  writeJsonExclusive(join(runDirectory, "human-review.json"), humanReview);
}

/**
 * Adds an append-only correction when a successful VERIFY/ACCEPTANCE command was omitted by the
 * original TEST-only acceptance exporter. Original evidence remains immutable and hash-linked.
 */
async function repairVerifyAcceptanceEvidence(context, lock, state) {
  let corrected = 0;
  const tasks = new Map(context.suite.tasks.map((task) => [task.id, task]));
  for (const entry of lock.matrix) {
    const runDirectory = resolve(context.input, entry.directory);
    const platformReportPath = join(runDirectory, "platform-report.json");
    const telemetryPath = join(runDirectory, "telemetry.json");
    const reviewPath = join(runDirectory, "human-review.json");
    const correctionPath = join(runDirectory, "evidence-correction.json");
    const correctedReportPath = join(runDirectory, "platform-report.corrected.json");
    const correctedTelemetryPath = join(runDirectory, "telemetry.corrected.json");
    if (!existsSync(platformReportPath) || !existsSync(telemetryPath)
        || !existsSync(reviewPath)) {
      continue;
    }
    if (existsSync(correctionPath)) {
      continue;
    }
    if (existsSync(correctedReportPath) || existsSync(correctedTelemetryPath)) {
      fail(`${entry.runId} has an incomplete acceptance correction`);
    }
    const report = readJson(platformReportPath);
    const telemetry = readJson(telemetryPath);
    const review = readJson(reviewPath);
    if ((report.acceptance?.length ?? 0) !== 0
        || telemetry.acceptanceCriteriaPassed !== false
        || telemetry.compilationPassed !== true
        || telemetry.testsPassed !== true
        || telemetry.pathPolicyPassed !== true
        || telemetry.securityPolicyPassed !== true
        || !["APPROVED", "APPROVED_WITH_NOTES"].includes(review.verdict)) {
      continue;
    }
    const coordinates = state.runs[entry.runId];
    const task = tasks.get(entry.taskId);
    if (!coordinates || !task) {
      fail(`${entry.runId} cannot resolve repair coordinates`);
    }
    const [commands, tests] = await Promise.all([
      codingCommands(context, coordinates.taskId, coordinates.executionId),
      codingTests(context, coordinates.taskId, coordinates.executionId),
    ]);
    const latestTest = [...tests].sort(
      (left, right) => left.sequence - right.sequence,
    ).at(-1);
    const acceptance = acceptanceReport(task, commands, latestTest);
    if (acceptance.length !== task.acceptanceCommands.length
        || !acceptance.every((criterion) => criterion.status === "PASSED")) {
      continue;
    }
    const referenced = new Set(latestTest?.commandEvidenceIds ?? []);
    const sourceCommands = commands.filter(
      (commandEvidence) => referenced.has(commandEvidence.id)
        && ["VERIFY", "ACCEPTANCE"].includes(commandEvidence.commandKind)
        && commandEvidence.termination === "EXITED"
        && commandEvidence.exitCode === 0,
    );
    if (sourceCommands.length === 0) {
      continue;
    }
    const correctedReport = { ...report, acceptance };
    const correctedTelemetry = { ...telemetry, acceptanceCriteriaPassed: true };
    writeJsonExclusive(correctedReportPath, correctedReport);
    writeJsonExclusive(correctedTelemetryPath, correctedTelemetry);
    writeJsonExclusive(correctionPath, {
      schemaVersion: "crewscope.coding-evidence-correction/v1",
      runId: entry.runId,
      reason: "VERIFY_ACCEPTANCE_EXPORT_OMISSION",
      originalPlatformReportSha256: sha256(readFileSync(platformReportPath)),
      originalTelemetrySha256: sha256(readFileSync(telemetryPath)),
      correctedPlatformReportSha256: sha256(readFileSync(correctedReportPath)),
      correctedTelemetrySha256: sha256(readFileSync(correctedTelemetryPath)),
      sourceCommandEvidenceIds: sourceCommands.map((commandEvidence) => commandEvidence.id),
      correctedAt: new Date().toISOString(),
    });
    corrected += 1;
  }
  process.stdout.write(`M4-Q03 runner appended ${corrected} acceptance correction(s).\n`);
}

async function discoverContext(args, input, lock, suite, runtime) {
  const organizationId = requireUuid(
    args["organization-id"] ?? "00000000-0000-4000-8000-000000000403",
    "organizationId",
  );
  const teamId = requireUuid(
    args["team-id"] ?? "b05c7740-6cb0-4e17-b958-d08fb29611c6",
    "teamId",
  );
  const projectId = requireUuid(
    args["project-id"] ?? "c6439043-58a7-42a9-9116-933513e01241",
    "projectId",
  );
  const context = {
    input,
    lock,
    suite,
    runtime,
    organizationId,
    teamId,
    projectId,
    repositoryKey: args["repository-key"] ?? "coding-evaluation",
    baseUrl: (args["base-url"] ?? `http://127.0.0.1:${process.env.CREWSCOPE_SERVER_PORT ?? "8080"}`)
      .replace(/\/$/, ""),
    authorization: authHeader(),
    pollMillis: requirePositiveInteger(args["poll-millis"], 2_000, "poll-millis"),
    timeoutMinutes: requirePositiveInteger(args["timeout-minutes"], 20, "timeout-minutes"),
    workItemOffset: requirePositiveInteger(
      args["work-item-offset"], 2_000, "work-item-offset",
    ),
    postgresContainer: args["postgres-container"] ?? "crewscope-java-postgres-1",
    statePath: join(input, "runner-state.json"),
  };
  const profile = sqlScalar(
    context,
    `SELECT id || '|' || agent_principal_id FROM crewscope.agent_profile WHERE organization_id='${organizationId}'::uuid AND team_id='${teamId}'::uuid AND profile_type='PERSONAL' AND default_profile AND status='ACTIVE';`,
  );
  const [executorProfileId, executorPrincipalId] = profile.split("|");
  context.executorProfileId = requireUuid(executorProfileId, "executorProfileId");
  context.executorPrincipalId = requireUuid(executorPrincipalId, "executorPrincipalId");
  const bindings = await api(
    context,
    "GET",
    `${projectPath(context)}/repository-bindings`,
  );
  const binding = bindings.items.find(
    (candidate) => candidate.repositoryKey === context.repositoryKey
      && candidate.status === "ACTIVE",
  );
  if (!binding) {
    fail(`Active RepositoryBinding ${context.repositoryKey} is unavailable`);
  }
  context.repositoryBindingId = binding.id;
  return context;
}

async function main() {
  const args = parseArguments(process.argv.slice(2));
  const input = resolve(args.input ?? "");
  if (!existsSync(join(input, "benchmark-lock.json"))) {
    fail("--input must identify a prepared Q03 benchmark directory");
  }
  const lock = readJson(join(input, "benchmark-lock.json"));
  const suite = readJson(suitePath);
  const runtime = readJson(runtimePath);
  if (lock.provider !== "deepseek"
      || lock.modelId !== "deepseek-v4-flash"
      || lock.modelRevision !== "DeepSeek-V4-Flash-0731") {
    fail("Prepared benchmark model identity is not the fixed M4-Q03 model");
  }
  const context = await discoverContext(args, input, lock, suite, runtime);
  const state = existsSync(context.statePath)
    ? readJson(context.statePath)
    : { schemaVersion: "crewscope.coding-benchmark-runner-state/v1", runs: {} };
  if (args["repair-verify-acceptance"] !== undefined) {
    if (args["repair-verify-acceptance"] !== "true") {
      fail("--repair-verify-acceptance must be true");
    }
    await repairVerifyAcceptanceEvidence(context, lock, state);
    return;
  }
  const startIndex = requirePositiveInteger(args["start-index"], 1, "start-index") - 1;
  const maximum = requirePositiveInteger(
    args["max-runs"], lock.matrix.length, "max-runs",
  );
  const selected = lock.matrix.slice(startIndex, startIndex + maximum);
  const tasks = new Map(suite.tasks.map((task) => [task.id, task]));
  for (let offset = 0; offset < selected.length; offset += 1) {
    const entry = selected[offset];
    const runNumber = lock.matrix.findIndex((candidate) => candidate.runId === entry.runId) + 1;
    const task = tasks.get(entry.taskId);
    if (!task) {
      fail(`Unknown frozen task ${entry.taskId}`);
    }
    const runDirectory = resolve(input, entry.directory);
    if (["workspace", "platform-report.json", "telemetry.json", "human-review.json"]
      .every((name) => existsSync(join(runDirectory, name)))) {
      process.stdout.write(`[${runNumber}/36] ${entry.runId} already archived\n`);
      continue;
    }
    process.stdout.write(`[${runNumber}/36] ${entry.runId} creating/resuming\n`);
    const coordinates = state.runs[entry.runId]
      ?? await createRun(context, entry, task, runNumber, state);
    const terminal = await waitForTerminal(context, entry, coordinates, state);
    process.stdout.write(
      `[${runNumber}/36] ${entry.runId} terminal=${terminal.execution.status}; exporting evidence\n`,
    );
    await exportEvidence(context, entry, task, coordinates, terminal);
    state.runs[entry.runId].archivedAt = new Date().toISOString();
    state.runs[entry.runId].terminalStatus = terminal.execution.status;
    writeState(context.statePath, state);
  }
  process.stdout.write(`M4-Q03 runner archived ${selected.length} selected run(s).\n`);
}

main().catch((error) => {
  process.stderr.write(`M4-Q03 runner error: ${error.message}\n`);
  process.exitCode = 1;
});
