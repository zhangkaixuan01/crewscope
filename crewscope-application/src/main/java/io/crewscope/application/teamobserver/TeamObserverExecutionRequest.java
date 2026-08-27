package io.crewscope.application.teamobserver;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.team.TeamMemberId;
import java.util.Objects;
import java.util.UUID;

/** Trusted coordinates passed to the AgentScope adapter after current membership authorization. */
public record TeamObserverExecutionRequest(
        OrganizationId organizationId,
        TeamId teamId,
        TeamMemberId memberId,
        Principal actor,
        TeamObserverSessionId sessionId,
        TeamObserverInvocationId invocationId,
        String instruction,
        int maxItemsPerSection,
        UUID correlationId) {

    public TeamObserverExecutionRequest {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        memberId = Objects.requireNonNull(memberId, "memberId");
        actor = Objects.requireNonNull(actor, "actor");
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        invocationId = Objects.requireNonNull(invocationId, "invocationId");
        instruction = requireInstruction(instruction);
        if (maxItemsPerSection < 1 || maxItemsPerSection > 50) {
            throw new IllegalArgumentException("maxItemsPerSection must be between 1 and 50");
        }
        correlationId = Objects.requireNonNull(correlationId, "correlationId");
    }

    private static String requireInstruction(String value) {
        String normalized = Objects.requireNonNull(value, "instruction").strip();
        if (normalized.isEmpty() || normalized.length() > 4_000) {
            throw new IllegalArgumentException("Team Observer instruction must be bounded text");
        }
        return normalized;
    }
}
