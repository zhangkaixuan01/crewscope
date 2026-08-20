package io.crewscope.agentscope.coding;

import io.crewscope.application.execution.TaskExecutionRuntimeFacts;
import io.crewscope.domain.coding.TestEvidence;
import java.util.Optional;

/** M4-I12 boundary to the M4-A03 Workspace, Tool Session and finalization lifecycle. */
public interface CodingSpecialistAuthorityGateway {

    /** Reconciles M4-I10 Workspace resources before durable AgentState is restored. */
    void recover(TaskExecutionRuntimeFacts facts);

    CodingSpecialistRound openRound(
            TaskExecutionRuntimeFacts facts,
            int round,
            Optional<TestEvidence> previousFailedEvidence);

    /** Re-reads Git, command and test authority after the model call has reached a safe point. */
    CodingSpecialistAuthority inspect(TaskExecutionRuntimeFacts facts, int round);

    /** Freezes the successful Diff and returns final platform-owned result coordinates. */
    default CodingSpecialistAuthority finalizeAuthority(
            TaskExecutionRuntimeFacts facts, int round) {
        return inspect(facts, round);
    }

    /** Releases the exclusive Tool window on success, failure, pause and cancellation. */
    default void closeRound(TaskExecutionRuntimeFacts facts, int round) {}
}
