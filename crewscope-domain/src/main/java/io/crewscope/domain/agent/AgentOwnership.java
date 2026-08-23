package io.crewscope.domain.agent;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.team.TeamMemberId;
import java.util.Objects;
import java.util.Optional;

/** Explicit ownership coordinate kept independent from runtime role and template capability. */
public record AgentOwnership(
        AgentOwnershipType type,
        OrganizationId organizationId,
        Optional<TeamId> teamId,
        Optional<TeamMemberId> ownerMemberId) {

    public AgentOwnership {
        type = Objects.requireNonNull(type, "type");
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        ownerMemberId = Objects.requireNonNull(ownerMemberId, "ownerMemberId");
        switch (type) {
            case USER -> {
                if (teamId.isEmpty() || ownerMemberId.isEmpty()) {
                    throw new DomainValidationException(
                            "agentOwnership",
                            "USER ownership requires a Team and owner member");
                }
            }
            case TEAM -> {
                if (teamId.isEmpty() || ownerMemberId.isPresent()) {
                    throw new DomainValidationException(
                            "agentOwnership",
                            "TEAM ownership requires a Team and no owner member");
                }
            }
            case ORGANIZATION -> {
                if (teamId.isPresent() || ownerMemberId.isPresent()) {
                    throw new DomainValidationException(
                            "agentOwnership",
                            "ORGANIZATION ownership must not reference a Team or member");
                }
            }
        }
    }

    public static AgentOwnership user(
            OrganizationId organizationId, TeamId teamId, TeamMemberId ownerMemberId) {
        return new AgentOwnership(
                AgentOwnershipType.USER,
                organizationId,
                Optional.of(Objects.requireNonNull(teamId, "teamId")),
                Optional.of(Objects.requireNonNull(ownerMemberId, "ownerMemberId")));
    }

    public static AgentOwnership team(OrganizationId organizationId, TeamId teamId) {
        return new AgentOwnership(
                AgentOwnershipType.TEAM,
                organizationId,
                Optional.of(Objects.requireNonNull(teamId, "teamId")),
                Optional.empty());
    }

    public static AgentOwnership organization(OrganizationId organizationId) {
        return new AgentOwnership(
                AgentOwnershipType.ORGANIZATION,
                organizationId,
                Optional.empty(),
                Optional.empty());
    }
}
