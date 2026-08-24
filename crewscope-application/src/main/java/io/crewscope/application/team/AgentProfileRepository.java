package io.crewscope.application.team;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.shared.id.TeamId;
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

    /** Lists the exact Team-scoped profiles visible to one active member. */
    default List<AgentProfile> findVisibleToMember(
            OrganizationId organizationId,
            TeamId teamId,
            TeamMemberId memberId,
            int offset,
            int limit) {
        throw new UnsupportedOperationException("Member-visible Agent list is not implemented");
    }

    /** Lists all exact Team-scoped profiles for an authorized Agent administrator. */
    default List<AgentProfile> findByTeam(
            OrganizationId organizationId, TeamId teamId, int offset, int limit) {
        throw new UnsupportedOperationException("Team Agent list is not implemented");
    }
}
