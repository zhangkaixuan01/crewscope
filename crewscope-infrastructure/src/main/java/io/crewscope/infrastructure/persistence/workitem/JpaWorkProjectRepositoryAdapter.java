package io.crewscope.infrastructure.persistence.workitem;

import io.crewscope.application.workitem.WorkProjectRepository;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.workitem.WorkProject;
import io.crewscope.domain.workitem.WorkProjectId;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Tenant-scoped JPA adapter for WorkProject lifecycle. */
@Repository
public class JpaWorkProjectRepositoryAdapter implements WorkProjectRepository {
    private final WorkPersistenceMapper mapper;
    @PersistenceContext private EntityManager entityManager;

    public JpaWorkProjectRepositoryAdapter(WorkPersistenceMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    @Transactional
    public WorkProject create(WorkProject value) {
        WorkProject required = Objects.requireNonNull(value, "project");
        if (required.version() != 0) {
            throw new DomainValidationException("workProject.version", "must be zero when created");
        }
        entityManager.persist(mapper.toEntity(required));
        entityManager.flush();
        return required;
    }

    @Override
    @Transactional
    public WorkProject update(WorkProject value) {
        WorkProject required = Objects.requireNonNull(value, "project");
        if (required.version() <= 0) {
            throw new DomainValidationException(
                    "workProject.version", "must contain one uncommitted domain mutation");
        }
        long expected = required.version() - 1;
        int affected =
                entityManager
                        .createQuery(
                                """
                                UPDATE WorkProjectEntity value SET value.name = :name, value.status = :status,
                                    value.updatedAt = :updatedAt, value.updatedBy = :updatedBy,
                                    value.version = :version
                                WHERE value.organizationId = :organizationId AND value.teamId = :teamId
                                  AND value.workspaceId = :workspaceId AND value.id = :id
                                  AND value.version = :expected
                                """)
                        .setParameter("name", required.name())
                        .setParameter("status", required.status().name())
                        .setParameter("updatedAt", required.audit().updatedAt().value())
                        .setParameter(
                                "updatedBy", required.audit().updatedBy().orElseThrow().value())
                        .setParameter("version", required.version())
                        .setParameter("organizationId", required.scope().organizationId().value())
                        .setParameter("teamId", required.scope().teamId().value())
                        .setParameter("workspaceId", required.scope().workspaceId().value())
                        .setParameter("id", required.id().value())
                        .setParameter("expected", expected)
                        .executeUpdate();
        entityManager.clear();
        verify(affected, required, expected);
        return findById(required.scope().organizationId(), required.id()).orElseThrow();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WorkProject> findById(OrganizationId organizationId, WorkProjectId id) {
        return entityManager
                .createQuery(
                        """
                        SELECT value FROM WorkProjectEntity value
                        WHERE value.organizationId = :organizationId AND value.id = :id
                        """,
                        WorkProjectEntity.class)
                .setParameter("organizationId", Objects.requireNonNull(organizationId).value())
                .setParameter("id", Objects.requireNonNull(id).value())
                .getResultStream()
                .findFirst()
                .map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<WorkProject> findByTeam(OrganizationId organizationId, TeamId teamId) {
        return entityManager
                .createQuery(
                        """
                        SELECT value FROM WorkProjectEntity value
                        WHERE value.organizationId = :organizationId AND value.teamId = :teamId
                        ORDER BY value.updatedAt DESC, value.id DESC
                        """,
                        WorkProjectEntity.class)
                .setParameter("organizationId", Objects.requireNonNull(organizationId).value())
                .setParameter("teamId", Objects.requireNonNull(teamId).value())
                .getResultList()
                .stream()
                .map(mapper::toDomain)
                .toList();
    }

    private void verify(int affected, WorkProject value, long expected) {
        if (affected != 0) {
            return;
        }
        Optional<Long> actual =
                entityManager
                        .createQuery(
                                """
                                SELECT value.version FROM WorkProjectEntity value
                                WHERE value.organizationId = :organizationId AND value.teamId = :teamId
                                  AND value.workspaceId = :workspaceId AND value.id = :id
                                """,
                                Long.class)
                        .setParameter("organizationId", value.scope().organizationId().value())
                        .setParameter("teamId", value.scope().teamId().value())
                        .setParameter("workspaceId", value.scope().workspaceId().value())
                        .setParameter("id", value.id().value())
                        .getResultStream()
                        .findFirst();
        if (actual.isEmpty()) {
            throw new AggregateNotFoundException("WorkProject", value.id());
        }
        throw new OptimisticLockConflictException(
                "WorkProject", value.id(), expected, actual.orElseThrow());
    }
}
