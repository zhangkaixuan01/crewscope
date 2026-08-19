package io.crewscope.agentscope.coding;

import io.crewscope.application.execution.ExecutionInterruptToken;
import io.crewscope.application.execution.TaskAgentStateIdentity;
import io.crewscope.application.execution.TaskExecutionRuntimeFacts;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.task.TaskAgentSessionPurpose;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Closed command for event-first AgentState and CodingCheckpoint publication. */
public record CodingSpecialistCheckpointCommand(
        TaskExecutionRuntimeFacts facts,
        CodingSpecialistStateSnapshot state,
        CodingSpecialistAuthority authority,
        CodingSpecialistCheckpointKind kind,
        long eventSequence,
        String safeSummary,
        Optional<ExecutionInterruptToken> interruptToken,
        Principal executor,
        UUID correlationId) {

    public CodingSpecialistCheckpointCommand {
        facts = Objects.requireNonNull(facts, "facts");
        state = Objects.requireNonNull(state, "state");
        authority = Objects.requireNonNull(authority, "authority");
        kind = Objects.requireNonNull(kind, "kind");
        if (eventSequence < 1) {
            throw new IllegalArgumentException("eventSequence must be positive");
        }
        safeSummary = Objects.requireNonNull(safeSummary, "safeSummary").strip();
        if (safeSummary.isEmpty() || safeSummary.length() > 500) {
            throw new IllegalArgumentException("safeSummary must be non-blank and bounded");
        }
        interruptToken = Objects.requireNonNull(interruptToken, "interruptToken");
        if ((kind == CodingSpecialistCheckpointKind.PAUSED) != interruptToken.isPresent()) {
            throw new IllegalArgumentException("only PAUSED checkpoints require an interrupt token");
        }
        executor = Objects.requireNonNull(executor, "executor");
        correlationId = Objects.requireNonNull(correlationId, "correlationId");
        requireStateBoundary(facts, state);
    }

    private static void requireStateBoundary(
            TaskExecutionRuntimeFacts facts, CodingSpecialistStateSnapshot state) {
        var session = facts.runtimeSession();
        var key = session.agentScopeKey();
        String stableId = TaskAgentStateIdentity.stableAgentId(
                session.agentProfileId(), session.agentProfileVersion(), session.purpose());
        if (facts.stepExecution().isEmpty()
                || session.purpose() != TaskAgentSessionPurpose.SPECIALIST
                || !stableId.equals(state.stableAgentId())
                || !key.userId().equals(state.userId())
                || !key.sessionId().equals(state.sessionId())) {
            throw new IllegalArgumentException(
                    "Coding safe point crossed its Step, Agent or Session boundary");
        }
    }
}
