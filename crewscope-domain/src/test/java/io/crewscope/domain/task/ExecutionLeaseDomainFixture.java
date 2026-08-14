package io.crewscope.domain.task;

import io.crewscope.domain.runtime.ExecutionRuntime;
import io.crewscope.domain.runtime.ExecutionRuntimeId;
import io.crewscope.domain.runtime.RuntimeCapabilities;
import io.crewscope.domain.runtime.RuntimeCapability;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.runtime.RuntimeProfile;
import io.crewscope.domain.runtime.RuntimeWorker;
import io.crewscope.domain.runtime.RuntimeWorkerCapacity;
import io.crewscope.domain.runtime.RuntimeWorkerId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.time.Duration;
import java.util.Set;

final class ExecutionLeaseDomainFixture {

    static final UtcTimestamp READY_AT = UtcTimestamp.parse("2026-08-13T08:05:00Z");
    static final UtcTimestamp CLAIM_AT = UtcTimestamp.parse("2026-08-13T08:10:00Z");
    static final UtcTimestamp PREPARE_AT = UtcTimestamp.parse("2026-08-13T08:11:00Z");
    static final UtcTimestamp RUN_AT = UtcTimestamp.parse("2026-08-13T08:12:00Z");
    static final UtcTimestamp FINISH_AT = UtcTimestamp.parse("2026-08-13T08:20:00Z");
    static final UtcTimestamp PREPARE_EXPIRY =
            UtcTimestamp.parse("2026-08-13T08:15:00Z");
    static final UtcTimestamp RUN_EXPIRY = UtcTimestamp.parse("2026-08-13T08:20:00Z");
    static final ClaimToken CLAIM_TOKEN =
            new ClaimToken("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ");

    final TaskDomainFixture taskFixture = new TaskDomainFixture();
    final RuntimeEnvironment environment = new RuntimeEnvironment("production");
    final RuntimeCapabilities capabilities = RuntimeCapabilities.of(
            Set.of(RuntimeCapability.CONVERSATION, RuntimeCapability.PLAN),
            Set.of("java"),
            Set.of("maven"));
    final ExecutionRuntime runtime = ExecutionRuntime.register(
            ExecutionRuntimeId.generate(),
            taskFixture.scope.organizationId(),
            environment,
            "agentscope-java",
            "AgentScope Java",
            "2.0.0",
            capabilities,
            taskFixture.owner,
            TaskDomainFixture.CREATED_AT);
    final RuntimeWorker worker = RuntimeWorker.register(
                    RuntimeWorkerId.generate(),
                    runtime,
                    "crewscope-worker-01",
                    RuntimeProfile.WORKER,
                    capabilities,
                    new RuntimeWorkerCapacity(4, 0),
                    taskFixture.executor,
                    TaskDomainFixture.CREATED_AT)
            .activate(0, taskFixture.executor, CLAIM_AT);

    TaskExecution claimedExecution() {
        return TaskExecution.firstAttempt(
                        TaskExecutionId.generate(),
                        taskFixture.task(),
                        3,
                        TaskExecutionPriority.NORMAL,
                        READY_AT,
                        taskFixture.owner,
                        TaskDomainFixture.CREATED_AT)
                .markReady(0, taskFixture.owner, READY_AT)
                .claim(1, taskFixture.executor, CLAIM_AT);
    }

    TaskExecution runningExecution(TaskExecution claimed) {
        return claimed.beginPreparing(2, taskFixture.executor, PREPARE_AT)
                .beginRunning(3, taskFixture.executor, RUN_AT);
    }

    ExecutionLease lease(TaskExecution claimed) {
        return ExecutionLease.acquire(
                ExecutionLeaseId.generate(),
                claimed,
                runtime,
                worker,
                capabilities,
                Duration.ofMinutes(2),
                CLAIM_TOKEN,
                CLAIM_AT,
                PREPARE_EXPIRY);
    }

    LeaseOwnership ownership(TaskExecution execution) {
        return new LeaseOwnership(
                execution.id(),
                execution.attempt(),
                runtime.id(),
                worker.id(),
                CLAIM_TOKEN.hash(),
                FencingToken.initial());
    }
}
