package io.crewscope.infrastructure.persistence.workgraph;

import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workgraph.WorkItemRelation;
import io.crewscope.domain.workgraph.WorkItemRelationId;
import io.crewscope.domain.workgraph.WorkItemRelationType;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Static entity ↔ domain mapping for WorkItemRelation edges. */
final class WorkItemRelationEntityMapper {

    private WorkItemRelationEntityMapper() {}

    static WorkItemRelationEntity toEntity(WorkItemRelation value) {
        WorkItemRelation source = Objects.requireNonNull(value, "relation");
        return new WorkItemRelationEntity(
                source.id().value(),
                source.src().scope().organizationId().value(),
                source.src().scope().teamId().value(),
                source.src().scope().workspaceId().value(),
                source.src().scope().projectId().value(),
                source.src().workItemId().value(),
                source.dst().scope().workspaceId().value(),
                source.dst().scope().projectId().value(),
                source.dst().workItemId().value(),
                source.kind().name(),
                source.audit().createdAt().value(),
                source.audit().createdBy().orElseThrow().value(),
                source.audit().updatedAt().value(),
                source.audit().updatedBy().orElseThrow().value());
    }

    static WorkItemRelation toDomain(WorkItemRelationEntity value) {
        UUID organizationId = value.organizationId();
        UUID teamId = value.teamId();
        return WorkItemRelation.reconstitute(
                new WorkItemRelationId(value.id()),
                new WorkItemRelation.RelationEndpoint(
                        new WorkItemId(value.srcWorkItemId()),
                        scope(organizationId, teamId, value.srcWorkspaceId(), value.srcProjectId())),
                new WorkItemRelation.RelationEndpoint(
                        new WorkItemId(value.dstWorkItemId()),
                        scope(organizationId, teamId, value.dstWorkspaceId(), value.dstProjectId())),
                WorkItemRelationType.valueOf(value.relationKind()),
                audit(value.createdBy(), value.createdAt(), value.updatedBy(), value.updatedAt()));
    }

    private static WorkItemScope scope(
            UUID organizationId, UUID teamId, UUID workspaceId, UUID projectId) {
        return new WorkItemScope(
                new OrganizationId(organizationId),
                new TeamId(teamId),
                new WorkspaceId(workspaceId),
                new WorkProjectId(projectId));
    }

    private static AuditMetadata audit(
            UUID createdBy, Instant createdAt, UUID updatedBy, Instant updatedAt) {
        return new AuditMetadata(
                Optional.of(new PrincipalId(createdBy)),
                UtcTimestamp.from(createdAt),
                Optional.of(new PrincipalId(updatedBy)),
                UtcTimestamp.from(updatedAt));
    }
}
