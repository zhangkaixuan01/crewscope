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
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkItemStatus;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Maps the persistence record to the framework-free WorkItem aggregate and back. */
@Component
public final class WorkItemEntityMapper {

    private static final String M0_ITEM_TYPE = "TASK";
    private static final String M0_PRIORITY = "MEDIUM";
    private static final String NATIVE_SOURCE = "CREWSCOPE";

    /** Creates a new Entity using the fixed M0 defaults for fields implemented in M1. */
    public WorkItemEntity toNewEntity(WorkItem workItem) {
        return new WorkItemEntity(
                workItem.id().value(),
                workItem.scope().organizationId().value(),
                workItem.scope().teamId().value(),
                workItem.scope().workspaceId().value(),
                workItem.scope().projectId().value(),
                workItem.key().value(),
                M0_ITEM_TYPE,
                workItem.title(),
                null,
                workItem.status().name(),
                M0_PRIORITY,
                NATIVE_SOURCE,
                null,
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
                entity.title(),
                WorkItemStatus.valueOf(entity.status()),
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
