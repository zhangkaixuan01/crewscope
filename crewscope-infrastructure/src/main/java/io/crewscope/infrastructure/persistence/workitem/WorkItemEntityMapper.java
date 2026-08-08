package io.crewscope.infrastructure.persistence.workitem;

import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemKey;
import io.crewscope.domain.workitem.WorkItemLabel;
import io.crewscope.domain.workitem.WorkItemPriority;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkItemSource;
import io.crewscope.domain.workitem.WorkItemStatus;
import io.crewscope.domain.workitem.WorkItemType;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Maps the persistence record to the framework-free WorkItem aggregate and back. */
@Component
public final class WorkItemEntityMapper {

    /** Creates a new Entity containing every product field owned by the M1 aggregate. */
    public WorkItemEntity toNewEntity(WorkItem workItem) {
        return new WorkItemEntity(
                workItem.id().value(),
                workItem.scope().organizationId().value(),
                workItem.scope().teamId().value(),
                workItem.scope().workspaceId().value(),
                workItem.scope().projectId().value(),
                workItem.key().value(),
                workItem.type().name(),
                workItem.title(),
                workItem.description().orElse(null),
                workItem.status().name(),
                workItem.priority().name(),
                workItem.source().name(),
                workItem.sourceReference().orElse(null),
                workItem.labels().stream().map(WorkItemLabel::value).sorted().toList(),
                workItem.dueAt().map(UtcTimestamp::value).orElse(null),
                workItem.version(),
                workItem.audit().createdAt().value(),
                workItem.audit().createdBy().map(PrincipalId::value).orElse(null),
                workItem.audit().updatedAt().value(),
                workItem.audit().updatedBy().map(PrincipalId::value).orElse(null));
    }

    /** Reconstitutes one immutable aggregate from a committed Entity snapshot. */
    public WorkItem toDomain(WorkItemEntity entity) {
        return WorkItem.reconstitute(
                new WorkItemId(entity.id()),
                new WorkItemScope(
                        new OrganizationId(entity.organizationId()),
                        new TeamId(entity.teamId()),
                        new WorkspaceId(entity.workspaceId()),
                        new WorkProjectId(entity.projectId())),
                new WorkItemKey(entity.itemKey()),
                WorkItemType.valueOf(entity.itemType()),
                entity.title(),
                Optional.ofNullable(entity.description()),
                WorkItemStatus.valueOf(entity.status()),
                WorkItemPriority.valueOf(entity.priority()),
                entity.labels().stream()
                        .map(WorkItemLabel::new)
                        .collect(Collectors.toUnmodifiableSet()),
                Optional.ofNullable(entity.dueAt()).map(UtcTimestamp::from),
                WorkItemSource.valueOf(entity.sourceProvider()),
                Optional.ofNullable(entity.sourceRef()),
                entity.version(),
                new AuditMetadata(
                        optionalPrincipal(entity.createdByPrincipalId()),
                        UtcTimestamp.from(entity.createdAt()),
                        optionalPrincipal(entity.updatedByPrincipalId()),
                        UtcTimestamp.from(entity.updatedAt())));
    }

    private static Optional<PrincipalId> optionalPrincipal(java.util.UUID value) {
        return Optional.ofNullable(value).map(PrincipalId::new);
    }
}
