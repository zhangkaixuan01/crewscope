package io.crewscope.application.review;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.review.ContextPackage;
import io.crewscope.domain.review.ReviewRequest;
import io.crewscope.domain.task.PolicySnapshot;
import io.crewscope.domain.task.TaskAgentRuntimeSession;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.UUID;

/** Closed facts handed to the AgentScope Reviewer adapter after application authorization. */
public record ReviewerExecutionCommand(
        ReviewRequest reviewRequest,
        ContextPackage contextPackage,
        PolicySnapshot policySnapshot,
        TaskAgentRuntimeSession runtimeSession,
        Principal reviewerAgent,
        UUID correlationId,
        UtcTimestamp observedAt) {

    public ReviewerExecutionCommand {
        reviewRequest = Objects.requireNonNull(reviewRequest, "reviewRequest");
        contextPackage = Objects.requireNonNull(contextPackage, "contextPackage");
        policySnapshot = Objects.requireNonNull(policySnapshot, "policySnapshot");
        runtimeSession = Objects.requireNonNull(runtimeSession, "runtimeSession");
        reviewerAgent = Objects.requireNonNull(reviewerAgent, "reviewerAgent");
        correlationId = Objects.requireNonNull(correlationId, "correlationId");
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
    }
}
