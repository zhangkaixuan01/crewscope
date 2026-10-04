package io.crewscope.domain.agent;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.workspace.AgentProfileId;
import java.util.Objects;

/**
 * The four fixed coordinates of one member's memory space on one Agent (M10-I02a, S01 §3.6):
 * organization + team + owning member principal + Agent profile. The policy version completes
 * the five-part owner key at the entry level, so the owner row itself is policy-independent.
 */
public record AgentMemoryOwnerKey(
        OrganizationId organizationId,
        TeamId teamId,
        AgentProfileId agentProfileId,
        PrincipalId ownerPrincipalId) {

    public AgentMemoryOwnerKey {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(agentProfileId, "agentProfileId");
        Objects.requireNonNull(ownerPrincipalId, "ownerPrincipalId");
    }
}
