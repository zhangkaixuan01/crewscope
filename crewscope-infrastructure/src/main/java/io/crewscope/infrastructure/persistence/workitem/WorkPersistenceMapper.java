package io.crewscope.infrastructure.persistence.workitem;

import static io.crewscope.infrastructure.persistence.PersistenceMappingSupport.audit;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.workitem.WorkItemComment;
import io.crewscope.domain.workitem.WorkItemCommentId;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemResourceLink;
import io.crewscope.domain.workitem.WorkItemResourceLinkId;
import io.crewscope.domain.workitem.WorkItemResourceType;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkItemSource;
import io.crewscope.domain.workitem.WorkProject;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.domain.workitem.WorkProjectKey;
import io.crewscope.domain.workitem.WorkProjectScope;
import io.crewscope.domain.workitem.WorkProjectStatus;

import org.springframework.stereotype.Component;

import java.util.Optional;

/** Maps M1 project and immutable collaboration facts to scalar JPA entities. */
@Component
public final class WorkPersistenceMapper {

    public WorkProjectEntity toEntity(WorkProject value) {
        return new WorkProjectEntity(
                value.id().value(),
                value.scope().organizationId().value(),
                value.scope().teamId().value(),
                value.scope().workspaceId().value(),
                value.key().value(),
                value.name(),
                value.status().name(),
                value.version(),
                value.audit().createdAt().value(),
                value.audit().createdBy().map(PrincipalId::value).orElse(null),
                value.audit().updatedAt().value(),
                value.audit().updatedBy().map(PrincipalId::value).orElse(null));
    }

    public WorkProject toDomain(WorkProjectEntity value) {
        return WorkProject.reconstitute(
                new WorkProjectId(value.id()),
                new WorkProjectScope(
                        new OrganizationId(value.organizationId()),
                        new TeamId(value.teamId()),
                        new WorkspaceId(value.workspaceId())),
                new WorkProjectKey(value.projectKey()),
                value.name(),
                WorkProjectStatus.valueOf(value.status()),
                value.version(),
                audit(value.createdBy(), value.createdAt(), value.updatedBy(), value.updatedAt()));
    }

    public WorkItemCommentEntity toEntity(WorkItemComment value) {
        return new WorkItemCommentEntity(
                value.id().value(),
                value.scope().organizationId().value(),
                value.scope().teamId().value(),
                value.scope().workspaceId().value(),
                value.scope().projectId().value(),
                value.workItemId().value(),
                value.authorPrincipalId().value(),
                value.content(),
                value.source().name(),
                value.externalId().orElse(null),
                value.audit().createdAt().value(),
                value.audit().createdBy().orElseThrow().value(),
                value.audit().updatedAt().value(),
                value.audit().updatedBy().orElseThrow().value());
    }

    public WorkItemComment toDomain(WorkItemCommentEntity value) {
        return WorkItemComment.reconstitute(
                new WorkItemCommentId(value.id()),
                scope(
                        value.organizationId(),
                        value.teamId(),
                        value.workspaceId(),
                        value.projectId()),
                new WorkItemId(value.workItemId()),
                new PrincipalId(value.authorPrincipalId()),
                value.content(),
                WorkItemSource.valueOf(value.sourceProvider()),
                Optional.ofNullable(value.externalId()),
                audit(value.createdBy(), value.createdAt(), value.updatedBy(), value.updatedAt()));
    }

    public WorkItemResourceLinkEntity toEntity(WorkItemResourceLink value) {
        return new WorkItemResourceLinkEntity(
                value.id().value(),
                value.scope().organizationId().value(),
                value.scope().teamId().value(),
                value.scope().workspaceId().value(),
                value.scope().projectId().value(),
                value.workItemId().value(),
                value.resourceType().name(),
                value.resourceReference(),
                value.label().orElse(null),
                value.audit().createdAt().value(),
                value.audit().createdBy().orElseThrow().value(),
                value.audit().updatedAt().value(),
                value.audit().updatedBy().orElseThrow().value());
    }

    public WorkItemResourceLink toDomain(WorkItemResourceLinkEntity value) {
        return WorkItemResourceLink.reconstitute(
                new WorkItemResourceLinkId(value.id()),
                scope(
                        value.organizationId(),
                        value.teamId(),
                        value.workspaceId(),
                        value.projectId()),
                new WorkItemId(value.workItemId()),
                WorkItemResourceType.valueOf(value.resourceType()),
                value.resourceReference(),
                Optional.ofNullable(value.label()),
                audit(value.createdBy(), value.createdAt(), value.updatedBy(), value.updatedAt()));
    }

    private static WorkItemScope scope(
            java.util.UUID organizationId,
            java.util.UUID teamId,
            java.util.UUID workspaceId,
            java.util.UUID projectId) {
        return new WorkItemScope(
                new OrganizationId(organizationId),
                new TeamId(teamId),
                new WorkspaceId(workspaceId),
                new WorkProjectId(projectId));
    }
}
