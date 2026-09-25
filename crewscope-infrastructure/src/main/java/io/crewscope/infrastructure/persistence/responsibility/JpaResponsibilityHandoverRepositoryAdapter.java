package io.crewscope.infrastructure.persistence.responsibility;

import io.crewscope.application.responsibility.ResponsibilityHandoverRepository;
import io.crewscope.domain.responsibility.handover.ResponsibilityHandoverItem;
import io.crewscope.domain.responsibility.handover.ResponsibilityHandoverItemId;
import io.crewscope.domain.responsibility.handover.ResponsibilityHandoverJob;
import io.crewscope.domain.responsibility.handover.ResponsibilityHandoverJobId;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JPA handover adapter. The job and item row locks (FOR UPDATE) serialize process/cancel against
 * one job and keep each item single-settled; status updates use optimistic versions as the lock
 * predicate, mirroring the responsibility adapter.
 */
@Repository
public class JpaResponsibilityHandoverRepositoryAdapter
        implements ResponsibilityHandoverRepository {

    private final ResponsibilityHandoverPersistenceMapper mapper;
    @PersistenceContext private EntityManager entityManager;

    public JpaResponsibilityHandoverRepositoryAdapter(
            ResponsibilityHandoverPersistenceMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    @Transactional
    public ResponsibilityHandoverJob createJob(
            ResponsibilityHandoverJob job, List<ResponsibilityHandoverItem> items) {
        ResponsibilityHandoverJob required = Objects.requireNonNull(job, "job");
        List<ResponsibilityHandoverItem> requiredItems = List.copyOf(items);
        if (required.version() != 0) {
            throw new DomainValidationException(
                    "responsibilityHandoverJob.version", "must be zero when created");
        }
        entityManager.persist(mapper.toEntity(required));
        requiredItems.forEach(
                item -> {
                    if (!item.jobId().equals(required.id())) {
                        throw new DomainValidationException(
                                "responsibilityHandoverItem.jobId", "must reference the job");
                    }
                    entityManager.persist(mapper.toEntity(item));
                });
        entityManager.flush();
        return required;
    }

    @Override
    @Transactional
    public Optional<ResponsibilityHandoverJob> lockJobById(
            OrganizationId organizationId, ResponsibilityHandoverJobId jobId) {
        return findJob(organizationId, jobId, true);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ResponsibilityHandoverJob> findJobById(
            OrganizationId organizationId, ResponsibilityHandoverJobId jobId) {
        return findJob(organizationId, jobId, false);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ResponsibilityHandoverJob> findJobByCommandId(
            OrganizationId organizationId, String commandId) {
        return entityManager
                .createQuery(
                        """
                        SELECT value FROM ResponsibilityHandoverJobEntity value
                        WHERE value.organizationId = :organizationId AND value.commandId = :commandId
                        """,
                        ResponsibilityHandoverJobEntity.class)
                .setParameter(
                        "organizationId", Objects.requireNonNull(organizationId).value())
                .setParameter(
                        "commandId", Objects.requireNonNull(commandId, "commandId"))
                .getResultStream()
                .findFirst()
                .map(mapper::toDomain);
    }

    @Override
    @Transactional
    public ResponsibilityHandoverJob updateJob(ResponsibilityHandoverJob value) {
        ResponsibilityHandoverJob required = Objects.requireNonNull(value, "job");
        if (required.version() <= 0) {
            throw new DomainValidationException(
                    "responsibilityHandoverJob.version",
                    "must contain one uncommitted domain mutation");
        }
        long expected = required.version() - 1;
        int affected =
                entityManager
                        .createQuery(
                                """
                                UPDATE ResponsibilityHandoverJobEntity value
                                SET value.status = :status, value.updatedAt = :updatedAt,
                                    value.updatedByPrincipalId = :updatedBy, value.version = :version
                                WHERE value.organizationId = :organizationId AND value.id = :id
                                  AND value.version = :expected
                                """)
                        .setParameter("status", required.status().name())
                        .setParameter("updatedAt", required.audit().updatedAt().value())
                        .setParameter(
                                "updatedBy",
                                required.audit().updatedBy().orElseThrow().value())
                        .setParameter("version", required.version())
                        .setParameter(
                                "organizationId", required.organizationId().value())
                        .setParameter("id", required.id().value())
                        .setParameter("expected", expected)
                        .executeUpdate();
        entityManager.clear();
        verifyJobUpdate(affected, required, expected);
        return findJobById(required.organizationId(), required.id()).orElseThrow();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ResponsibilityHandoverItem> findItems(
            OrganizationId organizationId, ResponsibilityHandoverJobId jobId) {
        return entityManager
                .createQuery(
                        """
                        SELECT value FROM ResponsibilityHandoverItemEntity value
                        WHERE value.organizationId = :organizationId AND value.jobId = :jobId
                        ORDER BY value.createdAt, value.id
                        """,
                        ResponsibilityHandoverItemEntity.class)
                .setParameter(
                        "organizationId", Objects.requireNonNull(organizationId).value())
                .setParameter("jobId", Objects.requireNonNull(jobId).value())
                .getResultList()
                .stream()
                .map(mapper::toDomain)
                .toList();
    }

    @Override
    @Transactional
    public Optional<ResponsibilityHandoverItem> lockItemById(
            OrganizationId organizationId, ResponsibilityHandoverItemId itemId) {
        return entityManager
                .createQuery(
                        """
                        SELECT value FROM ResponsibilityHandoverItemEntity value
                        WHERE value.organizationId = :organizationId AND value.id = :id
                        """,
                        ResponsibilityHandoverItemEntity.class)
                .setParameter(
                        "organizationId", Objects.requireNonNull(organizationId).value())
                .setParameter("id", Objects.requireNonNull(itemId).value())
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getResultStream()
                .findFirst()
                .map(mapper::toDomain);
    }

    @Override
    @Transactional
    public ResponsibilityHandoverItem updateItem(ResponsibilityHandoverItem value) {
        ResponsibilityHandoverItem required = Objects.requireNonNull(value, "item");
        if (required.version() <= 0) {
            throw new DomainValidationException(
                    "responsibilityHandoverItem.version",
                    "must contain one uncommitted domain mutation");
        }
        long expected = required.version() - 1;
        int affected =
                entityManager
                        .createQuery(
                                """
                                UPDATE ResponsibilityHandoverItemEntity value
                                SET value.state = :state, value.resultAssignmentId = :result,
                                    value.errorCode = :errorCode, value.processedAt = :processedAt,
                                    value.updatedAt = :updatedAt, value.updatedByPrincipalId = :updatedBy,
                                    value.version = :version
                                WHERE value.organizationId = :organizationId AND value.id = :id
                                  AND value.jobId = :jobId AND value.version = :expected
                                """)
                        .setParameter("state", required.state().name())
                        .setParameter(
                                "result",
                                required.resultAssignmentId().isEmpty()
                                        ? null
                                        : required.resultAssignmentId().orElseThrow().value())
                        .setParameter("errorCode", required.errorCode().orElse(null))
                        .setParameter(
                                "processedAt",
                                required.processedAt().map(item -> item.value()).orElse(null))
                        .setParameter("updatedAt", required.audit().updatedAt().value())
                        .setParameter(
                                "updatedBy",
                                required.audit().updatedBy().orElseThrow().value())
                        .setParameter("version", required.version())
                        .setParameter(
                                "organizationId", required.organizationId().value())
                        .setParameter("jobId", required.jobId().value())
                        .setParameter("id", required.id().value())
                        .setParameter("expected", expected)
                        .executeUpdate();
        entityManager.clear();
        verifyItemUpdate(affected, required, expected);
        return entityManager
                .createQuery(
                        """
                        SELECT value FROM ResponsibilityHandoverItemEntity value
                        WHERE value.organizationId = :organizationId AND value.id = :id
                        """,
                        ResponsibilityHandoverItemEntity.class)
                .setParameter("organizationId", required.organizationId().value())
                .setParameter("id", required.id().value())
                .getResultStream()
                .findFirst()
                .map(mapper::toDomain)
                .orElseThrow();
    }

    private Optional<ResponsibilityHandoverJob> findJob(
            OrganizationId organizationId, ResponsibilityHandoverJobId jobId, boolean lock) {
        var query =
                entityManager
                        .createQuery(
                                """
                                SELECT value FROM ResponsibilityHandoverJobEntity value
                                WHERE value.organizationId = :organizationId AND value.id = :id
                                """,
                                ResponsibilityHandoverJobEntity.class)
                        .setParameter(
                                "organizationId",
                                Objects.requireNonNull(organizationId).value())
                        .setParameter("id", Objects.requireNonNull(jobId).value());
        if (lock) {
            query.setLockMode(LockModeType.PESSIMISTIC_WRITE);
        }
        return query.getResultStream().findFirst().map(mapper::toDomain);
    }

    private void verifyJobUpdate(
            int affected, ResponsibilityHandoverJob value, long expected) {
        if (affected != 0) {
            return;
        }
        Optional<Long> actual =
                entityManager
                        .createQuery(
                                """
                                SELECT item.version FROM ResponsibilityHandoverJobEntity item
                                WHERE item.organizationId = :organizationId AND item.id = :id
                                """,
                                Long.class)
                        .setParameter(
                                "organizationId", value.organizationId().value())
                        .setParameter("id", value.id().value())
                        .getResultStream()
                        .findFirst();
        if (actual.isEmpty()) {
            throw new AggregateNotFoundException("ResponsibilityHandoverJob", value.id());
        }
        throw new OptimisticLockConflictException(
                "ResponsibilityHandoverJob", value.id(), expected, actual.orElseThrow());
    }

    private void verifyItemUpdate(
            int affected, ResponsibilityHandoverItem value, long expected) {
        if (affected != 0) {
            return;
        }
        Optional<Long> actual =
                entityManager
                        .createQuery(
                                """
                                SELECT item.version FROM ResponsibilityHandoverItemEntity item
                                WHERE item.organizationId = :organizationId AND item.id = :id
                                """,
                                Long.class)
                        .setParameter(
                                "organizationId", value.organizationId().value())
                        .setParameter("id", value.id().value())
                        .getResultStream()
                        .findFirst();
        if (actual.isEmpty()) {
            throw new AggregateNotFoundException("ResponsibilityHandoverItem", value.id());
        }
        throw new OptimisticLockConflictException(
                "ResponsibilityHandoverItem", value.id(), expected, actual.orElseThrow());
    }
}
