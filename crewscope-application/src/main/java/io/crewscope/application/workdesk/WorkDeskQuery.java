package io.crewscope.application.workdesk;

import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Objects;
import java.util.Optional;

/** Fully scoped, read-only query for the current member's personal work desk. */
public record WorkDeskQuery(
    OrganizationId organizationId,
    TeamId teamId,
    TeamMemberId memberId,
    PrincipalId principalId,
    Optional<WorkProjectId> projectId,
    Optional<ResponsibilityRole> responsibilityRole,
    boolean onlyNeedsAction,
    int sectionLimit) {

    /** The page size every section's first screen asks for when the caller does not pin one. */
    public static final int DEFAULT_SECTION_LIMIT = 20;
    /** The largest section page the contract serves; a bigger ask is a caller error. */
    public static final int MAX_SECTION_LIMIT = 100;

    public WorkDeskQuery {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        memberId = Objects.requireNonNull(memberId, "memberId");
        principalId = Objects.requireNonNull(principalId, "principalId");
        projectId = Objects.requireNonNull(projectId, "projectId");
        responsibilityRole = Objects.requireNonNull(responsibilityRole, "responsibilityRole");
        if (sectionLimit < 1 || sectionLimit > MAX_SECTION_LIMIT) {
            throw new IllegalArgumentException(
                "sectionLimit must be between 1 and " + MAX_SECTION_LIMIT);
        }
    }

    /** The shape callers that never continue a section use: the default page size per section. */
    public WorkDeskQuery(
        OrganizationId organizationId,
        TeamId teamId,
        TeamMemberId memberId,
        PrincipalId principalId,
        Optional<WorkProjectId> projectId,
        Optional<ResponsibilityRole> responsibilityRole,
        boolean onlyNeedsAction) {
        this(organizationId, teamId, memberId, principalId, projectId,
            responsibilityRole, onlyNeedsAction, DEFAULT_SECTION_LIMIT);
    }

    /** The same query with a section page size the HTTP layer validated. */
    public WorkDeskQuery withSectionLimit(int limit) {
        return new WorkDeskQuery(organizationId, teamId, memberId, principalId, projectId,
            responsibilityRole, onlyNeedsAction, limit);
    }
}
