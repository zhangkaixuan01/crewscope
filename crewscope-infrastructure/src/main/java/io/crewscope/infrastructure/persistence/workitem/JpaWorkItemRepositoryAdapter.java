package io.crewscope.infrastructure.persistence.workitem;

import io.crewscope.application.workitem.WorkItemCursor;
import io.crewscope.application.workitem.WorkItemCursorScope;
import io.crewscope.application.workitem.WorkItemFilter;
import io.crewscope.application.workitem.WorkItemPage;
import io.crewscope.application.workitem.WorkItemQuery;
import io.crewscope.application.workitem.WorkItemRepository;
import io.crewscope.application.workitem.WorkItemSort;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkItemKey;
import io.crewscope.domain.workitem.WorkProjectId;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
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
    public Optional<WorkItem> findByKey(
            OrganizationId organizationId, WorkProjectId projectId, WorkItemKey key) {
        return entityManager
                .createQuery(
                        """
                        SELECT item FROM WorkItemEntity item
                        WHERE item.organizationId = :organizationId
                          AND item.projectId = :projectId
                          AND item.itemKey = :itemKey
                        """,
                        WorkItemEntity.class)
                .setParameter("organizationId", Objects.requireNonNull(organizationId).value())
                .setParameter("projectId", Objects.requireNonNull(projectId).value())
                .setParameter("itemKey", Objects.requireNonNull(key).value())
                .getResultStream()
                .findFirst()
                .map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public WorkItemKey nextKey(
            OrganizationId organizationId,
            io.crewscope.domain.workitem.WorkProject project) {
        OrganizationId organization = Objects.requireNonNull(organizationId, "organizationId");
        io.crewscope.domain.workitem.WorkProject required =
                Objects.requireNonNull(project, "project");
        if (!required.scope().organizationId().equals(organization)) {
            throw new DomainValidationException(
                    "workItem.key", "project must belong to the requested Organization");
        }
        String prefix = required.key().value();
        Object raw = entityManager
                .createNativeQuery(
                        """
                        SELECT COALESCE(
                            MAX(CAST(SUBSTRING(item_key FROM CHAR_LENGTH(:prefix) + 2) AS NUMERIC)),
                            0
                        )
                        FROM crewscope.work_item
                        WHERE organization_id = :organizationId
                          AND project_id = :projectId
                          AND item_key ~ :pattern
                        """)
                .setParameter("prefix", prefix)
                .setParameter("organizationId", organization.value())
                .setParameter("projectId", required.id().value())
                .setParameter("pattern", "^" + prefix + "-[1-9][0-9]*$")
                .getSingleResult();
        BigDecimal maximum = raw instanceof BigDecimal decimal
                ? decimal
                : new BigDecimal(raw.toString());
        return new WorkItemKey(
                prefix + "-" + maximum.toBigIntegerExact().add(java.math.BigInteger.ONE));
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
                  AND item.projectId = :projectId
                """);
        WorkItemFilter filter = required.filter();
        if (!filter.statuses().isEmpty()) {
            jpql.append(" AND item.status IN :statuses");
        }
        if (!filter.types().isEmpty()) {
            jpql.append(" AND item.itemType IN :types");
        }
        if (!filter.priorities().isEmpty()) {
            jpql.append(" AND item.priority IN :priorities");
        }
        filter.responsibilityRole().ifPresent(role -> jpql.append(
                """
                 AND EXISTS (
                      SELECT assignment FROM ResponsibilityAssignmentEntity assignment
                      WHERE assignment.organizationId = item.organizationId
                        AND assignment.teamId = item.teamId
                        AND assignment.workspaceId = item.workspaceId
                        AND assignment.projectId = item.projectId
                        AND assignment.workItemId = item.id
                        AND assignment.status = 'ACTIVE'
                        AND assignment.role = :responsibilityRole)
                """));
        required.after().ifPresent(cursor -> jpql.append(
                required.sort() == WorkItemSort.DUE_AT && cursor.primaryTime().isEmpty()
                        ? nullSegmentPredicate()
                        : keysetPredicate(required.sort())));
        jpql.append(orderClause(required.sort()));

        var persistenceQuery = entityManager
                .createQuery(jpql.toString(), WorkItemEntity.class)
                .setParameter("organizationId", required.organizationId().value())
                .setParameter("teamId", required.teamId().value())
                .setParameter("projectId", required.projectId().value())
                .setMaxResults(required.limit() + 1);
        if (!filter.statuses().isEmpty()) {
            persistenceQuery.setParameter(
                    "statuses", filter.statuses().stream().map(Enum::name).toList());
        }
        if (!filter.types().isEmpty()) {
            persistenceQuery.setParameter(
                    "types", filter.types().stream().map(Enum::name).toList());
        }
        if (!filter.priorities().isEmpty()) {
            persistenceQuery.setParameter(
                    "priorities", filter.priorities().stream().map(Enum::name).toList());
        }
        filter.responsibilityRole()
                .ifPresent(role -> persistenceQuery.setParameter(
                        "responsibilityRole", role.name()));
        required.after().ifPresent(cursor -> {
            cursor.primaryTime().ifPresent(time ->
                    persistenceQuery.setParameter("cursorPrimaryTime", time.value()));
            cursor.primaryRank().ifPresent(rank ->
                    persistenceQuery.setParameter("cursorPrimaryRank", rank));
            persistenceQuery.setParameter("cursorId", cursor.id().value());
        });

        List<WorkItemEntity> rows = new ArrayList<>(persistenceQuery.getResultList());
        boolean hasNext = rows.size() > required.limit();
        if (hasNext) {
            rows.remove(rows.size() - 1);
        }
        List<WorkItem> items = rows.stream().map(mapper::toDomain).toList();
        Optional<WorkItemCursor> nextCursor = hasNext
                ? Optional.of(toCursor(
                        rows.get(rows.size() - 1), required.cursorScope(), required.sort()))
                : Optional.empty();
        return new WorkItemPage(items, nextCursor);
    }

    /**
     * The keyset predicate that continues strictly after the cursor's position for one ordering.
     *
     * <p>Descending orderings compare with {@code <}; {@code DUE_AT} ascends with nulls last, so a
     * cursor inside the dated segment also admits the whole null segment, and a cursor already inside
     * the null segment continues by ID only. The ID tie-breaker always follows the primary direction,
     * which is what makes the total order stable for equal primary values.
     */
    private static String keysetPredicate(WorkItemSort sort) {
        return switch (sort) {
            case UPDATED_AT -> """
                 AND (item.updatedAt < :cursorPrimaryTime
                      OR (item.updatedAt = :cursorPrimaryTime AND item.id < :cursorId))
                """;
            case CREATED_AT -> """
                 AND (item.createdAt < :cursorPrimaryTime
                      OR (item.createdAt = :cursorPrimaryTime AND item.id < :cursorId))
                """;
            case PRIORITY -> """
                 AND (PRIORITY_RANK < :cursorPrimaryRank
                      OR (PRIORITY_RANK = :cursorPrimaryRank AND item.id < :cursorId))
                """.replace("PRIORITY_RANK", PRIORITY_RANK);
            case DUE_AT -> """
                 AND (item.dueAt IS NULL
                      OR item.dueAt > :cursorPrimaryTime
                      OR (item.dueAt = :cursorPrimaryTime AND item.id > :cursorId))
                """;
        };
    }

    private static String orderClause(WorkItemSort sort) {
        return switch (sort) {
            case UPDATED_AT -> " ORDER BY item.updatedAt DESC, item.id DESC";
            case CREATED_AT -> " ORDER BY item.createdAt DESC, item.id DESC";
            case PRIORITY -> " ORDER BY " + PRIORITY_RANK + " DESC, item.id DESC";
            case DUE_AT -> " ORDER BY item.dueAt ASC NULLS LAST, item.id ASC";
        };
    }

    /**
     * The DUE_AT continuation used once the traversal has entered the trailing null-due-time
     * segment; the generic predicate above would bind a time parameter this cursor no longer has.
     */
    private static String nullSegmentPredicate() {
        return " AND item.dueAt IS NULL AND item.id > :cursorId";
    }

    private static final String PRIORITY_RANK =
            "CASE item.priority WHEN 'URGENT' THEN 4 WHEN 'HIGH' THEN 3"
                    + " WHEN 'MEDIUM' THEN 2 WHEN 'LOW' THEN 1 ELSE 0 END";

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

    /** Builds the continuation cursor for one ordering from the last row of a full page. */
    private static WorkItemCursor toCursor(
            WorkItemEntity entity, WorkItemCursorScope scope, WorkItemSort sort) {
        Optional<UtcTimestamp> primaryTime = Optional.empty();
        OptionalInt primaryRank = OptionalInt.empty();
        switch (sort) {
            case UPDATED_AT -> primaryTime = Optional.of(UtcTimestamp.from(entity.updatedAt()));
            case CREATED_AT -> primaryTime = Optional.of(UtcTimestamp.from(entity.createdAt()));
            case PRIORITY -> primaryRank = OptionalInt.of(priorityRank(entity.priority()));
            case DUE_AT -> primaryTime = entity.dueAt() == null
                    ? Optional.empty()
                    : Optional.of(UtcTimestamp.from(entity.dueAt()));
        }
        return new WorkItemCursor(scope, primaryTime, primaryRank, new WorkItemId(entity.id()));
    }

    private static int priorityRank(String priority) {
        return switch (priority == null ? "" : priority) {
            case "URGENT" -> 4;
            case "HIGH" -> 3;
            case "MEDIUM" -> 2;
            case "LOW" -> 1;
            default -> 0;
        };
    }
}
