package io.crewscope.infrastructure.persistence.workgraph;

import io.crewscope.application.workgraph.WorkItemRelationRepository;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.workgraph.WorkItemRelation;
import io.crewscope.domain.workgraph.WorkItemRelationConflictException;
import io.crewscope.domain.workgraph.WorkItemRelationCycleException;
import io.crewscope.domain.workgraph.WorkItemRelationId;
import io.crewscope.domain.workgraph.WorkItemSplitSourceConflictException;
import io.crewscope.domain.workitem.WorkItemId;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * JPA write-path adapter for WorkItemRelation edges. Concurrent losers of the lock-and-probe
 * protocol are rejected by the recursive CTE inside the lock; the unique-index translations
 * below are the database backstop for any path that bypasses the protocol.
 */
@Repository
public class JpaWorkItemRelationRepositoryAdapter implements WorkItemRelationRepository {
    @PersistenceContext private EntityManager entityManager;

    @Override
    @Transactional
    public WorkItemRelation create(WorkItemRelation value) {
        WorkItemRelation required = Objects.requireNonNull(value, "relation");
        try {
            entityManager.persist(WorkItemRelationEntityMapper.toEntity(required));
            entityManager.flush();
        } catch (RuntimeException failure) {
            // flush raises the raw Hibernate ConstraintViolationException inside this method;
            // Spring only translates it to DataIntegrityViolationException at the proxy, after
            // this catch — so match on the constraint name across the whole cause chain and
            // rethrow anything that is not a known unique-index violation
            throw translateUniqueViolation(required, failure);
        }
        return required;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WorkItemRelation> findById(
            OrganizationId organizationId, WorkItemRelationId id) {
        return entityManager
                .createQuery(
                        """
                        SELECT value FROM WorkItemRelationEntity value
                        WHERE value.organizationId = :organizationId AND value.id = :id
                        """,
                        WorkItemRelationEntity.class)
                .setParameter("organizationId", Objects.requireNonNull(organizationId).value())
                .setParameter("id", Objects.requireNonNull(id).value())
                .getResultStream()
                .findFirst()
                .map(WorkItemRelationEntityMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WorkItemRelation> findByStoredEndpoints(
            OrganizationId organizationId, WorkItemId src, WorkItemId dst) {
        return entityManager
                .createQuery(
                        """
                        SELECT value FROM WorkItemRelationEntity value
                        WHERE value.organizationId = :organizationId
                          AND value.srcWorkItemId = :src AND value.dstWorkItemId = :dst
                        """,
                        WorkItemRelationEntity.class)
                .setParameter("organizationId", Objects.requireNonNull(organizationId).value())
                .setParameter("src", Objects.requireNonNull(src, "src").value())
                .setParameter("dst", Objects.requireNonNull(dst, "dst").value())
                .getResultStream()
                .findFirst()
                .map(WorkItemRelationEntityMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<WorkItemRelation> findByEndpoint(
            OrganizationId organizationId, WorkItemId endpoint) {
        return entityManager
                .createQuery(
                        """
                        SELECT value FROM WorkItemRelationEntity value
                        WHERE value.organizationId = :organizationId
                          AND (value.srcWorkItemId = :endpoint OR value.dstWorkItemId = :endpoint)
                        ORDER BY value.createdAt, value.id
                        """,
                        WorkItemRelationEntity.class)
                .setParameter("organizationId", Objects.requireNonNull(organizationId).value())
                .setParameter("endpoint", Objects.requireNonNull(endpoint, "endpoint").value())
                .getResultList()
                .stream()
                .map(WorkItemRelationEntityMapper::toDomain)
                .toList();
    }

    @Override
    @Transactional
    public Optional<WorkItemRelation> removeById(
            OrganizationId organizationId, WorkItemRelationId id) {
        Optional<WorkItemRelationEntity> existing =
                entityManager
                        .createQuery(
                                """
                                SELECT value FROM WorkItemRelationEntity value
                                WHERE value.organizationId = :organizationId AND value.id = :id
                                """,
                                WorkItemRelationEntity.class)
                        .setParameter(
                                "organizationId", Objects.requireNonNull(organizationId).value())
                        .setParameter("id", Objects.requireNonNull(id).value())
                        .getResultStream()
                        .findFirst();
        existing.ifPresent(
                entity -> {
                    entityManager.remove(entity);
                    entityManager.flush();
                });
        return existing.map(WorkItemRelationEntityMapper::toDomain);
    }

    @Override
    @Transactional
    public List<WorkItemRelation> removeByWorkItem(
            OrganizationId organizationId, WorkItemId workItem) {
        List<WorkItemRelationEntity> touching =
                entityManager
                        .createQuery(
                                """
                                SELECT value FROM WorkItemRelationEntity value
                                WHERE value.organizationId = :organizationId
                                  AND (value.srcWorkItemId = :endpoint
                                       OR value.dstWorkItemId = :endpoint)
                                ORDER BY value.createdAt, value.id
                                """,
                                WorkItemRelationEntity.class)
                        .setParameter(
                                "organizationId", Objects.requireNonNull(organizationId).value())
                        .setParameter(
                                "endpoint", Objects.requireNonNull(workItem, "workItem").value())
                        .getResultList();
        for (WorkItemRelationEntity entity : touching) {
            entityManager.remove(entity);
        }
        entityManager.flush();
        return touching.stream().map(WorkItemRelationEntityMapper::toDomain).toList();
    }

    private static RuntimeException translateUniqueViolation(
            WorkItemRelation required, RuntimeException failure) {
        // the constraint name may sit in the top-level message or only in the JDBC cause chain
        String message = constraintText(failure);
        if (message.contains("ux_work_item_relation_split_child")) {
            return new WorkItemSplitSourceConflictException(required.src().workItemId());
        }
        if (message.contains("ux_work_item_relation_block_pair")) {
            // the undirected pair index only fires for an opposite blocking edge: a 2-cycle
            return new WorkItemRelationCycleException(
                    required.src().workItemId(), required.dst().workItemId());
        }
        if (message.contains("ux_work_item_relation_forward")) {
            return new WorkItemRelationConflictException(
                    required.src().workItemId(), required.dst().workItemId());
        }
        return failure;
    }

    private static String constraintText(Throwable violation) {
        StringBuilder text = new StringBuilder(String.valueOf(violation.getMessage()));
        Throwable cause = violation.getCause();
        while (cause != null) {
            text.append(' ').append(String.valueOf(cause.getMessage()));
            cause = cause.getCause();
        }
        return text.toString();
    }
}
