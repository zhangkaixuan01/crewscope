package io.crewscope.domain.workspace.event;

import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.workspace.AgentProfile;
import java.util.Objects;

/** Public-safe Agent identity and lifecycle fact retained independently from mutable configuration. */
public record AgentProfileChanged(
        String agentProfileId,
        String agentPrincipalId,
        String ownershipType,
        String ownershipId,
        String runtimeRole,
        String templateKey,
        long templateVersion,
        String status,
        long version) implements DomainEvent {

    public AgentProfileChanged {
        Objects.requireNonNull(agentProfileId, "agentProfileId");
        Objects.requireNonNull(agentPrincipalId, "agentPrincipalId");
        Objects.requireNonNull(ownershipType, "ownershipType");
        Objects.requireNonNull(ownershipId, "ownershipId");
        Objects.requireNonNull(runtimeRole, "runtimeRole");
        Objects.requireNonNull(templateKey, "templateKey");
        Objects.requireNonNull(status, "status");
    }

    public static AgentProfileChanged from(AgentProfile profile) {
        AgentProfile value = Objects.requireNonNull(profile, "profile");
        String ownershipId = switch (value.ownership().type()) {
            case USER -> value.ownership().ownerMemberId().orElseThrow().toString();
            case TEAM -> value.ownership().teamId().orElseThrow().toString();
            case ORGANIZATION -> value.ownership().organizationId().toString();
        };
        return new AgentProfileChanged(
                value.id().toString(),
                value.agentPrincipalId().toString(),
                value.ownership().type().name(),
                ownershipId,
                value.runtimeRole().name(),
                value.templateVersion().key().toString(),
                value.templateVersion().version(),
                value.status().name(),
                value.version());
    }
}
