package io.crewscope.infrastructure.persistence.responsibility;

import static io.crewscope.infrastructure.persistence.PersistenceMappingSupport.audit;

import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.responsibility.ResponsibilityAssignment;
import io.crewscope.domain.responsibility.ResponsibilityAssignmentId;
import io.crewscope.domain.responsibility.ResponsibilityAssignmentStatus;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;

import org.springframework.stereotype.Component;

import java.util.Optional;

/** Maps responsibility facts to the complete WorkItem scope stored by V6. */
@Component
public final class ResponsibilityPersistenceMapper {
    public ResponsibilityAssignmentEntity toEntity(ResponsibilityAssignment value) {
        return new ResponsibilityAssignmentEntity(
                value.id().value(),
                value.scope().organizationId().value(),
                value.scope().teamId().value(),
                value.scope().workspaceId().value(),
                value.scope().projectId().value(),
                value.workItemId().value(),
                value.role().name(),
                value.actorPrincipalId().value(),
                value.actorType().name(),
                value.actorMemberId().map(TeamMemberId::value).orElse(null),
                value.status().name(),
                value.assignedByPrincipalId().value(),
                value.assignedAt().value(),
                value.acceptedAt().value(),
                value.releasedByPrincipalId().map(PrincipalId::value).orElse(null),
                value.releasedAt().map(UtcTimestamp::value).orElse(null),
                value.version(),
                value.audit().createdAt().value(),
                value.audit().createdBy().orElseThrow().value(),
                value.audit().updatedAt().value(),
                value.audit().updatedBy().orElseThrow().value());
    }

    public ResponsibilityAssignment toDomain(ResponsibilityAssignmentEntity value) {
        return ResponsibilityAssignment.reconstitute(
                new ResponsibilityAssignmentId(value.id()),
                new WorkItemScope(
                        new OrganizationId(value.organizationId()),
                        new TeamId(value.teamId()),
                        new WorkspaceId(value.workspaceId()),
                        new WorkProjectId(value.projectId())),
                new WorkItemId(value.workItemId()),
                ResponsibilityRole.valueOf(value.role()),
                new PrincipalId(value.actorPrincipalId()),
                PrincipalType.valueOf(value.actorType()),
                Optional.ofNullable(value.actorMemberId()).map(TeamMemberId::new),
                ResponsibilityAssignmentStatus.valueOf(value.status()),
                new PrincipalId(value.assignedBy()),
                UtcTimestamp.from(value.assignedAt()),
                UtcTimestamp.from(value.acceptedAt()),
                Optional.ofNullable(value.releasedBy()).map(PrincipalId::new),
                Optional.ofNullable(value.releasedAt()).map(UtcTimestamp::from),
                value.version(),
                audit(value.createdBy(), value.createdAt(), value.updatedBy(), value.updatedAt()));
    }
}
