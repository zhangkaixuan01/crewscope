package io.crewscope.agentscope.coding;

import io.crewscope.application.execution.TaskAgentStateRecoveryResult;
import io.crewscope.application.execution.TaskExecutionRuntimeFacts;
import io.crewscope.domain.identity.Principal;
import java.util.UUID;

/** Durable M3 execution operations used by the Coding coordinator. */
public interface CodingSpecialistExecutionStore {

    void beginStep(TaskExecutionRuntimeFacts facts, Principal executor);

    TaskAgentStateRecoveryResult recoverState(TaskExecutionRuntimeFacts facts, int candidateLimit);

    CodingSpecialistCheckpointReceipt checkpoint(CodingSpecialistCheckpointCommand command);

    void succeed(
            TaskExecutionRuntimeFacts facts,
            long eventSequence,
            Principal executor,
            UUID correlationId);

    void fail(
            TaskExecutionRuntimeFacts facts,
            long eventSequence,
            String failureCode,
            boolean retryable,
            Principal executor,
            UUID correlationId);
}
