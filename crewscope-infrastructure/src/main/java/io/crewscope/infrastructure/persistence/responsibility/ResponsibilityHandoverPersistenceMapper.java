package io.crewscope.infrastructure.persistence.responsibility;

import static io.crewscope.infrastructure.persistence.PersistenceMappingSupport.audit;

import io.crewscope.domain.responsibility.ResponsibilityAssignmentId;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.responsibility.handover.HandoverItemState;
import io.crewscope.domain.responsibility.handover.HandoverJobStatus;
import io.crewscope.domain.responsibility.handover.ResponsibilityHandoverItem;
import io.crewscope.domain.responsibility.handover.ResponsibilityHandoverItemId;
import io.crewscope.domain.responsibility.handover.ResponsibilityHandoverJob;
import io.crewscope.domain.responsibility.handover.ResponsibilityHandoverJobId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Maps handover job/item facts to the V45 columns, including each item's full WorkItem scope. */
@Component
public final class ResponsibilityHandoverPersistenceMapper {

    public ResponsibilityHandoverJobEntity toEntity(ResponsibilityHandoverJob value) {
        return new ResponsibilityHandoverJobEntity(
                value.id().value(),
                value.organizationId().value(),
                value.teamId().value(),
                value.sourceMemberId().value(),
                value.targetPrincipalId().value(),
                value.role().name(),
                value.commandId(),
                value.createdByPrincipalId().value(),
                value.sourceAuthorizationVersion(),
                value.status().name(),
                value.version(),
                value.audit().createdAt().value(),
                value.audit().updatedAt().value(),
                value.audit().updatedBy().orElseThrow().value());
    }

    public ResponsibilityHandoverJob toDomain(ResponsibilityHandoverJobEntity value) {
        return ResponsibilityHandoverJob.reconstitute(
                new ResponsibilityHandoverJobId(value.id()),
                new OrganizationId(value.organizationId()),
                new TeamId(value.teamId()),
                new TeamMemberId(value.sourceMemberId()),
                new PrincipalId(value.targetPrincipalId()),
                ResponsibilityRole.valueOf(value.role()),
                value.commandId(),
                new PrincipalId(value.createdByPrincipalId()),
                value.sourceAuthorizationVersion(),
                HandoverJobStatus.valueOf(value.status()),
                value.version(),
                audit(
                        value.createdByPrincipalId(),
                        value.createdAt(),
                        value.updatedByPrincipalId(),
                        value.updatedAt()));
    }

    public ResponsibilityHandoverItemEntity toEntity(ResponsibilityHandoverItem value) {
        return new ResponsibilityHandoverItemEntity(
                value.id().value(),
                value.organizationId().value(),
                value.jobId().value(),
                value.assignmentId().value(),
                value.workItemId().value(),
                value.scope().teamId().value(),
                value.scope().workspaceId().value(),
                value.scope().projectId().value(),
                value.expectedAssignmentVersion(),
                value.state().name(),
                value.resultAssignmentId().map(ResponsibilityAssignmentId::value).orElse(null),
                value.errorCode().orElse(null),
                value.processedAt().map(UtcTimestamp::value).orElse(null),
                value.version(),
                value.audit().createdAt().value(),
                value.audit().createdBy().orElseThrow().value(),
                value.audit().updatedAt().value(),
                value.audit().updatedBy().orElseThrow().value());
    }

    public ResponsibilityHandoverItem toDomain(ResponsibilityHandoverItemEntity value) {
        return ResponsibilityHandoverItem.reconstitute(
                new ResponsibilityHandoverItemId(value.id()),
                new OrganizationId(value.organizationId()),
                new ResponsibilityHandoverJobId(value.jobId()),
                new ResponsibilityAssignmentId(value.assignmentId()),
                new WorkItemId(value.workItemId()),
                new WorkItemScope(
                        new OrganizationId(value.organizationId()),
                        new TeamId(value.teamId()),
                        new WorkspaceId(value.workspaceId()),
                        new WorkProjectId(value.projectId())),
                value.expectedAssignmentVersion(),
                HandoverItemState.valueOf(value.state()),
                Optional.ofNullable(value.resultAssignmentId())
                        .map(ResponsibilityAssignmentId::new),
                Optional.ofNullable(value.errorCode()),
                Optional.ofNullable(value.processedAt()).map(UtcTimestamp::from),
                value.version(),
                audit(
                        value.createdByPrincipalId(),
                        value.createdAt(),
                        value.updatedByPrincipalId(),
                        value.updatedAt()));
    }
}
