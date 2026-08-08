package io.crewscope.application.team;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.team.TeamMember;
import java.util.List;

/** Read Port for Team membership facts required by application policy evaluation. */
public interface TeamMembershipQuery {

    /** Returns current and historical memberships inside one explicit tenant boundary. */
    List<TeamMember> findByTeam(OrganizationId organizationId, TeamId teamId);
}
