package io.crewscope.agentscope.coding;

import io.crewscope.application.execution.TaskExecutionRuntimeFacts;
import io.crewscope.domain.identity.Principal;
import java.util.Objects;
import java.util.UUID;

/** Trusted Worker request for one current Specialist Step and AgentRun Segment. */
public record CodingSpecialistStepRequest(
        TaskExecutionRuntimeFacts facts,
        Principal executor,
        long nextEventSequence,
        UUID correlationId,
        boolean recover,
        int recoveryCandidateLimit) {

    public CodingSpecialistStepRequest {
        facts = Objects.requireNonNull(facts, "facts");
        executor = Objects.requireNonNull(executor, "executor");
        if (nextEventSequence < 1) {
            throw new IllegalArgumentException("nextEventSequence must be positive");
        }
        correlationId = Objects.requireNonNull(correlationId, "correlationId");
        if (recoveryCandidateLimit < 1 || recoveryCandidateLimit > 100) {
            throw new IllegalArgumentException("recoveryCandidateLimit must be between 1 and 100");
        }
    }
}
