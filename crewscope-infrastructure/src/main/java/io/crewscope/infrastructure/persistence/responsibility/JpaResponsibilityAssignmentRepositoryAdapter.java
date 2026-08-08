package io.crewscope.infrastructure.persistence.responsibility;

import io.crewscope.application.responsibility.ResponsibilityAssignmentRepository;
import io.crewscope.domain.responsibility.ResponsibilityAssignment;
import io.crewscope.domain.responsibility.ResponsibilityAssignmentId;
import io.crewscope.domain.responsibility.ResponsibilityConflictException;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.workitem.WorkItemId;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** JPA responsibility adapter with a WorkItem row lock and stable slot-conflict semantics. */
@Repository
public class JpaResponsibilityAssignmentRepositoryAdapter
        implements ResponsibilityAssignmentRepository {
    private static final String OWNER_CONSTRAINT = "ux_responsibility_assignment_active_owner";
    private static final String ROLE_ACTOR_CONSTRAINT =
            "ux_responsibility_assignment_active_role_actor";

    private final ResponsibilityPersistenceMapper mapper;
    @PersistenceContext private EntityManager entityManager;

    public JpaResponsibilityAssignmentRepositoryAdapter(ResponsibilityPersistenceMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    @Transactional
    public void lockResponsibilityChain(OrganizationId organizationId, WorkItemId workItemId) {
        // Lock only the stable row identity; responsibility policies do not need the WorkItem
        // description, labels or any managed Entity state at this boundary.
        boolean exists =
                entityManager
                        .createNativeQuery(
                                """
                                SELECT id FROM crewscope.work_item
                                WHERE organization_id = :organizationId AND id = :workItemId
                                FOR UPDATE
                                """)
                        .setParameter(
                                "organizationId", Objects.requireNonNull(organizationId).value())
                        .setParameter("workItemId", Objects.requireNonNull(workItemId).value())
                        .getResultStream()
                        .findFirst()
                        .isPresent();
        if (!exists) {
            throw new AggregateNotFoundException("WorkItem", workItemId);
        }
    }

    @Override
    @Transactional
    public ResponsibilityAssignment create(ResponsibilityAssignment value) {
        ResponsibilityAssignment required = Objects.requireNonNull(value, "assignment");
        if (required.version() != 0) {
            throw new DomainValidationException(
                    "responsibilityAssignment.version", "must be zero when created");
        }
        try {
            entityManager.persist(mapper.toEntity(required));
            entityManager.flush();
            return required;
        } catch (RuntimeException failure) {
            if (isSlotConflict(failure)) {
                throw new ResponsibilityConflictException(
                        required.workItemId(), required.role(), required.actorPrincipalId());
            }
            throw failure;
        }
    }

    @Override
    @Transactional
    public ResponsibilityAssignment update(ResponsibilityAssignment value) {
        ResponsibilityAssignment required = Objects.requireNonNull(value, "assignment");
        if (required.version() <= 0) {
            throw new DomainValidationException(
                    "responsibilityAssignment.version",
                    "must contain one uncommitted domain mutation");
        }
        long expected = required.version() - 1;
        int affected =
                entityManager
                        .createQuery(
                                """
                                UPDATE ResponsibilityAssignmentEntity value
                                SET value.status = :status, value.releasedBy = :releasedBy,
                                    value.releasedAt = :releasedAt, value.updatedAt = :updatedAt,
                                    value.updatedBy = :updatedBy, value.version = :version
                                WHERE value.organizationId = :organizationId AND value.teamId = :teamId
                                  AND value.workspaceId = :workspaceId AND value.projectId = :projectId
                                  AND value.workItemId = :workItemId AND value.id = :id
                                  AND value.version = :expected
                                """)
                        .setParameter("status", required.status().name())
                        .setParameter(
                                "releasedBy",
                                required.releasedByPrincipalId()
                                        .map(PrincipalId::value)
                                        .orElse(null))
                        .setParameter(
                                "releasedAt",
                                required.releasedAt().map(t -> t.value()).orElse(null))
                        .setParameter("updatedAt", required.audit().updatedAt().value())
                        .setParameter(
                                "updatedBy", required.audit().updatedBy().orElseThrow().value())
                        .setParameter("version", required.version())
                        .setParameter("organizationId", required.scope().organizationId().value())
                        .setParameter("teamId", required.scope().teamId().value())
                        .setParameter("workspaceId", required.scope().workspaceId().value())
                        .setParameter("projectId", required.scope().projectId().value())
                        .setParameter("workItemId", required.workItemId().value())
                        .setParameter("id", required.id().value())
                        .setParameter("expected", expected)
                        .executeUpdate();
        entityManager.clear();
        verifyUpdate(affected, required, expected);
        return findById(required.scope().organizationId(), required.id()).orElseThrow();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ResponsibilityAssignment> findById(
            OrganizationId organizationId, ResponsibilityAssignmentId id) {
        return entityManager
                .createQuery(
                        """
                        SELECT value FROM ResponsibilityAssignmentEntity value
                        WHERE value.organizationId = :organizationId AND value.id = :id
                        """,
                        ResponsibilityAssignmentEntity.class)
                .setParameter("organizationId", Objects.requireNonNull(organizationId).value())
                .setParameter("id", Objects.requireNonNull(id).value())
                .getResultStream()
                .findFirst()
                .map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ResponsibilityAssignment> findActiveOwner(
            OrganizationId organizationId, WorkItemId workItemId) {
        return entityManager
                .createQuery(
                        """
                        SELECT value FROM ResponsibilityAssignmentEntity value
                        WHERE value.organizationId = :organizationId AND value.workItemId = :workItemId
                          AND value.role = 'OWNER' AND value.status = 'ACTIVE'
                        """,
                        ResponsibilityAssignmentEntity.class)
                .setParameter("organizationId", Objects.requireNonNull(organizationId).value())
                .setParameter("workItemId", Objects.requireNonNull(workItemId).value())
                .getResultStream()
                .findFirst()
                .map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ResponsibilityAssignment> findActiveByWorkItem(
            OrganizationId organizationId, WorkItemId workItemId) {
        return entityManager
                .createQuery(
                        """
                        SELECT value FROM ResponsibilityAssignmentEntity value
                        WHERE value.organizationId = :organizationId AND value.workItemId = :workItemId
                          AND value.status = 'ACTIVE'
                        ORDER BY value.role, value.assignedAt, value.id
                        """,
                        ResponsibilityAssignmentEntity.class)
                .setParameter("organizationId", Objects.requireNonNull(organizationId).value())
                .setParameter("workItemId", Objects.requireNonNull(workItemId).value())
                .getResultList()
                .stream()
                .map(mapper::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ResponsibilityAssignment> findActive(
            OrganizationId organizationId,
            WorkItemId workItemId,
            ResponsibilityRole role,
            PrincipalId actorPrincipalId) {
        return entityManager
                .createQuery(
                        """
                        SELECT value FROM ResponsibilityAssignmentEntity value
                        WHERE value.organizationId = :organizationId AND value.workItemId = :workItemId
                          AND value.role = :role AND value.actorPrincipalId = :actorPrincipalId
                          AND value.status = 'ACTIVE'
                        """,
                        ResponsibilityAssignmentEntity.class)
                .setParameter("organizationId", Objects.requireNonNull(organizationId).value())
                .setParameter("workItemId", Objects.requireNonNull(workItemId).value())
                .setParameter("role", Objects.requireNonNull(role).name())
                .setParameter("actorPrincipalId", Objects.requireNonNull(actorPrincipalId).value())
                .getResultStream()
                .findFirst()
                .map(mapper::toDomain);
    }

    private void verifyUpdate(int affected, ResponsibilityAssignment value, long expected) {
        if (affected != 0) {
            return;
        }
        Optional<Long> actual =
                entityManager
                        .createQuery(
                                """
                                SELECT item.version FROM ResponsibilityAssignmentEntity item
                                WHERE item.organizationId = :organizationId AND item.teamId = :teamId
                                  AND item.workspaceId = :workspaceId AND item.projectId = :projectId
                                  AND item.workItemId = :workItemId AND item.id = :id
                                """,
                                Long.class)
                        .setParameter("organizationId", value.scope().organizationId().value())
                        .setParameter("teamId", value.scope().teamId().value())
                        .setParameter("workspaceId", value.scope().workspaceId().value())
                        .setParameter("projectId", value.scope().projectId().value())
                        .setParameter("workItemId", value.workItemId().value())
                        .setParameter("id", value.id().value())
                        .getResultStream()
                        .findFirst();
        if (actual.isEmpty()) {
            throw new AggregateNotFoundException("ResponsibilityAssignment", value.id());
        }
        throw new OptimisticLockConflictException(
                "ResponsibilityAssignment", value.id(), expected, actual.orElseThrow());
    }

    private static boolean isSlotConflict(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            String message =
                    Optional.ofNullable(current.getMessage()).orElse("").toLowerCase(Locale.ROOT);
            if (message.contains(OWNER_CONSTRAINT) || message.contains(ROLE_ACTOR_CONSTRAINT)) {
                return true;
            }
        }
        return false;
    }
}
