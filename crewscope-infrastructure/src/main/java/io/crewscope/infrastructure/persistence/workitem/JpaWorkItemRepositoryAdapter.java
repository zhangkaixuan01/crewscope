package io.crewscope.infrastructure.persistence.workitem;

import io.crewscope.application.workitem.WorkItemCursor;
import io.crewscope.application.workitem.WorkItemPage;
import io.crewscope.application.workitem.WorkItemQuery;
import io.crewscope.application.workitem.WorkItemRepository;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemScope;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL/JPA adapter with explicit tenant predicates and atomic version-checked updates. */
@Repository
public class JpaWorkItemRepositoryAdapter implements WorkItemRepository {

    private final WorkItemEntityMapper mapper;

    @PersistenceContext
    private EntityManager entityManager;

    public JpaWorkItemRepositoryAdapter(WorkItemEntityMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    @Transactional
    public WorkItem create(WorkItem workItem) {
        WorkItem required = Objects.requireNonNull(workItem, "workItem");
        if (required.version() != 0) {
            throw new DomainValidationException("workItem.version", "must be zero when created");
        }
        WorkItemEntity entity = mapper.toNewEntity(required);
        entityManager.persist(entity);
        entityManager.flush();
        return mapper.toDomain(entity);
    }

    @Override
    @Transactional
    public WorkItem update(WorkItem workItem) {
        WorkItem required = Objects.requireNonNull(workItem, "workItem");
        long expectedVersion = required.version() - 1;
        if (expectedVersion < 0) {
            throw new DomainValidationException(
                    "workItem.version", "must contain one uncommitted domain mutation");
        }
        PrincipalId updatedBy = required.audit().updatedBy().orElseThrow(() ->
                new DomainValidationException("workItem.updatedBy", "must identify a Principal"));

        // The version predicate makes the state, modifier, timestamp and version one atomic write.
        int affected = entityManager
                .createQuery(
                        """
                        UPDATE WorkItemEntity item
                        SET item.itemKey = :itemKey,
                            item.itemType = :itemType,
                            item.title = :title,
                            item.description = :description,
                            item.status = :status,
                            item.priority = :priority,
                            item.labels = :labels,
                            item.dueAt = :dueAt,
                            item.sourceProvider = :sourceProvider,
                            item.sourceRef = :sourceRef,
                            item.updatedByPrincipalId = :updatedBy,
                            item.updatedAt = :updatedAt,
                            item.version = :committedVersion
                        WHERE item.organizationId = :organizationId
                          AND item.teamId = :teamId
                          AND item.workspaceId = :workspaceId
                          AND item.projectId = :projectId
                          AND item.id = :id
                          AND item.version = :expectedVersion
                        """)
                .setParameter("itemKey", required.key().value())
                .setParameter("itemType", required.type().name())
                .setParameter("title", required.title())
                .setParameter("description", required.description().orElse(null))
                .setParameter("status", required.status().name())
                .setParameter("priority", required.priority().name())
                .setParameter(
                        "labels",
                        required.labels().stream()
                                .map(io.crewscope.domain.workitem.WorkItemLabel::value)
                                .sorted()
                                .toList())
                .setParameter("dueAt", required.dueAt().map(UtcTimestamp::value).orElse(null))
                .setParameter("sourceProvider", required.source().name())
                .setParameter("sourceRef", required.sourceReference().orElse(null))
                .setParameter("updatedBy", updatedBy.value())
                .setParameter("updatedAt", required.audit().updatedAt().value())
                .setParameter("committedVersion", required.version())
                .setParameter("organizationId", required.scope().organizationId().value())
                .setParameter("teamId", required.scope().teamId().value())
                .setParameter("workspaceId", required.scope().workspaceId().value())
                .setParameter("projectId", required.scope().projectId().value())
                .setParameter("id", required.id().value())
                .setParameter("expectedVersion", expectedVersion)
                .executeUpdate();

        entityManager.clear();
        if (affected == 0) {
            Optional<Long> actualVersion = findVersion(required.scope(), required.id());
            if (actualVersion.isEmpty()) {
                throw new AggregateNotFoundException("WorkItem", required.id());
            }
            throw new OptimisticLockConflictException(
                    "WorkItem", required.id(), expectedVersion, actualVersion.orElseThrow());
        }
        return findEntity(required.scope().organizationId(), required.id())
                .map(mapper::toDomain)
                .orElseThrow(() -> new AggregateNotFoundException("WorkItem", required.id()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WorkItem> findById(OrganizationId organizationId, WorkItemId id) {
        return findEntity(
                        Objects.requireNonNull(organizationId, "organizationId"),
                        Objects.requireNonNull(id, "id"))
                .map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public WorkItemPage findPage(WorkItemQuery query) {
        WorkItemQuery required = Objects.requireNonNull(query, "query");
        StringBuilder jpql = new StringBuilder(
                """
                SELECT item FROM WorkItemEntity item
                WHERE item.organizationId = :organizationId
                  AND item.teamId = :teamId
                """);
        required.projectId().ifPresent(ignored -> jpql.append(" AND item.projectId = :projectId"));
        required.status().ifPresent(ignored -> jpql.append(" AND item.status = :status"));
        required.cursor().ifPresent(ignored -> jpql.append(
                """
                 AND (item.updatedAt < :cursorTime
                      OR (item.updatedAt = :cursorTime AND item.id < :cursorId))
                """));
        jpql.append(" ORDER BY item.updatedAt DESC, item.id DESC");

        var persistenceQuery = entityManager
                .createQuery(jpql.toString(), WorkItemEntity.class)
                .setParameter("organizationId", required.organizationId().value())
                .setParameter("teamId", required.teamId().value())
                .setMaxResults(required.limit() + 1);
        required.projectId()
                .ifPresent(projectId -> persistenceQuery.setParameter("projectId", projectId.value()));
        required.status()
                .ifPresent(status -> persistenceQuery.setParameter("status", status.name()));
        required.cursor().ifPresent(cursor -> {
            persistenceQuery.setParameter("cursorTime", cursor.updatedAt().value());
            persistenceQuery.setParameter("cursorId", cursor.id().value());
        });

        List<WorkItemEntity> rows = new ArrayList<>(persistenceQuery.getResultList());
        boolean hasNext = rows.size() > required.limit();
        if (hasNext) {
            rows.remove(rows.size() - 1);
        }
        List<WorkItem> items = rows.stream().map(mapper::toDomain).toList();
        Optional<WorkItemCursor> nextCursor = hasNext
                ? Optional.of(toCursor(rows.get(rows.size() - 1)))
                : Optional.empty();
        return new WorkItemPage(items, nextCursor);
    }

    private Optional<WorkItemEntity> findEntity(
            OrganizationId organizationId, WorkItemId id) {
        return entityManager
                .createQuery(
                        """
                        SELECT item FROM WorkItemEntity item
                        WHERE item.organizationId = :organizationId AND item.id = :id
                        """,
                        WorkItemEntity.class)
                .setParameter("organizationId", organizationId.value())
                .setParameter("id", id.value())
                .getResultStream()
                .findFirst();
    }

    private Optional<Long> findVersion(WorkItemScope scope, WorkItemId id) {
        return entityManager
                .createQuery(
                        """
                        SELECT item.version FROM WorkItemEntity item
                        WHERE item.organizationId = :organizationId
                          AND item.teamId = :teamId
                          AND item.workspaceId = :workspaceId
                          AND item.projectId = :projectId
                          AND item.id = :id
                        """,
                        Long.class)
                .setParameter("organizationId", scope.organizationId().value())
                .setParameter("teamId", scope.teamId().value())
                .setParameter("workspaceId", scope.workspaceId().value())
                .setParameter("projectId", scope.projectId().value())
                .setParameter("id", id.value())
                .getResultStream()
                .findFirst();
    }

    private static WorkItemCursor toCursor(WorkItemEntity entity) {
        return new WorkItemCursor(
                UtcTimestamp.from(entity.updatedAt()), new WorkItemId(entity.id()));
    }
}
