package io.crewscope.infrastructure.persistence.taskruntime;

import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.AggregateId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.task.TaskExecutionId;
import jakarta.persistence.EntityManager;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Shared scoped lookup and optimistic-version helpers for the small M3 JPA adapters. */
@Component
class TaskRuntimeJpaSupport {

    final EntityManager entityManager;
    final TaskRuntimeExtendedPersistenceMapper mapper;

    TaskRuntimeJpaSupport(
            EntityManager entityManager, TaskRuntimeExtendedPersistenceMapper mapper) {
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    <T extends WorkScopedRow> Optional<T> findScoped(
            Class<T> type, OrganizationId organizationId, UUID id) {
        String entityName = entityManager.getMetamodel().entity(type).getName();
        return entityManager.createQuery(
                        "SELECT row FROM " + entityName
                                + " row WHERE row.organizationId = :organizationId AND row.id = :id",
                        type)
                .setParameter("organizationId", organizationId.value())
                .setParameter("id", id)
                .getResultStream().findFirst();
    }

    UUID taskId(OrganizationId organizationId, TaskExecutionId executionId) {
        return entityManager.createQuery(
                        """
                        SELECT execution.taskId FROM TaskExecutionEntity execution
                        WHERE execution.organizationId = :organizationId
                          AND execution.id = :executionId
                        """,
                        UUID.class)
                .setParameter("organizationId", organizationId.value())
                .setParameter("executionId", executionId.value())
                .getResultStream().findFirst()
                .orElseThrow(() -> new AggregateNotFoundException("TaskExecution", executionId));
    }

    static long expected(long version, String field) {
        long expected = version - 1;
        if (expected < 0) {
            throw new IllegalArgumentException(field + " must contain one uncommitted mutation");
        }
        return expected;
    }

    static void requireVersion(
            String aggregate, AggregateId id, long expected, long actual) {
        if (actual != expected) {
            throw new OptimisticLockConflictException(aggregate, id, expected, actual);
        }
    }
}
