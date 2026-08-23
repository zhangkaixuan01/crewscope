package io.crewscope.application.team;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.workspace.AgentProfile;
import io.crewscope.domain.workspace.AgentProfileId;
import java.util.List;
import java.util.Optional;

/** Persistence Port for durable Agent product profiles. */
public interface AgentProfileRepository {

    /** Persists one non-default template-backed Agent profile. */
    AgentProfile create(AgentProfile profile);

    /** Commits one AgentProfile lifecycle change with an optimistic version predicate. */
    AgentProfile update(AgentProfile profile);

    Optional<AgentProfile> findById(OrganizationId organizationId, AgentProfileId id);

    Optional<AgentProfile> findActiveDefaultPersonal(
            OrganizationId organizationId, TeamMemberId ownerMemberId);

    /** Resolves the current runnable profile behind an Agent responsibility Principal. */
    Optional<AgentProfile> findActiveByAgentPrincipalId(
            OrganizationId organizationId, PrincipalId agentPrincipalId);

    /** Returns a stable updated-time window for non-conversational Agent management. */
    List<AgentProfile> findPage(OrganizationId organizationId, int offset, int limit);
}
