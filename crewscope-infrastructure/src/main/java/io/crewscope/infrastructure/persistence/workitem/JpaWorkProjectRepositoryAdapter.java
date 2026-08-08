package io.crewscope.infrastructure.persistence.workitem;

import io.crewscope.application.workitem.WorkProjectRepository;
import io.crewscope.application.workitem.WorkProjectCursor;
import io.crewscope.application.workitem.WorkProjectPage;
import io.crewscope.application.workitem.WorkProjectQuery;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.workitem.WorkProject;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.domain.workitem.WorkProjectKey;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.ArrayList;
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
    @Transactional
    public Optional<WorkProject> lockById(
            OrganizationId organizationId, WorkProjectId id) {
        return entityManager
                .createQuery(
                        """
                        SELECT value FROM WorkProjectEntity value
                        WHERE value.organizationId = :organizationId AND value.id = :id
                        """,
                        WorkProjectEntity.class)
                .setParameter("organizationId", Objects.requireNonNull(organizationId).value())
                .setParameter("id", Objects.requireNonNull(id).value())
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getResultStream()
                .findFirst()
                .map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WorkProject> findByKey(
            OrganizationId organizationId, TeamId teamId, WorkProjectKey key) {
        return entityManager
                .createQuery(
                        """
                        SELECT value FROM WorkProjectEntity value
                        WHERE value.organizationId = :organizationId
                          AND value.teamId = :teamId
                          AND value.projectKey = :projectKey
                        """,
                        WorkProjectEntity.class)
                .setParameter("organizationId", Objects.requireNonNull(organizationId).value())
                .setParameter("teamId", Objects.requireNonNull(teamId).value())
                .setParameter("projectKey", Objects.requireNonNull(key).value())
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

    @Override
    @Transactional(readOnly = true)
    public WorkProjectPage findPage(WorkProjectQuery query) {
        WorkProjectQuery required = Objects.requireNonNull(query, "query");
        StringBuilder jpql = new StringBuilder(
                """
                SELECT value FROM WorkProjectEntity value
                WHERE value.organizationId = :organizationId AND value.teamId = :teamId
                """);
        required.cursor().ifPresent(ignored -> jpql.append(
                """
                 AND (value.updatedAt < :cursorTime
                      OR (value.updatedAt = :cursorTime AND value.id < :cursorId))
                """));
        jpql.append(" ORDER BY value.updatedAt DESC, value.id DESC");

        var persistenceQuery = entityManager
                .createQuery(jpql.toString(), WorkProjectEntity.class)
                .setParameter("organizationId", required.organizationId().value())
                .setParameter("teamId", required.teamId().value())
                .setMaxResults(required.limit() + 1);
        required.cursor().ifPresent(cursor -> {
            persistenceQuery.setParameter("cursorTime", cursor.updatedAt().value());
            persistenceQuery.setParameter("cursorId", cursor.id().value());
        });

        List<WorkProjectEntity> rows = new ArrayList<>(persistenceQuery.getResultList());
        boolean hasNext = rows.size() > required.limit();
        if (hasNext) {
            rows.remove(rows.size() - 1);
        }
        List<WorkProject> projects = rows.stream().map(mapper::toDomain).toList();
        Optional<WorkProjectCursor> nextCursor = hasNext
                ? Optional.of(toCursor(rows.get(rows.size() - 1)))
                : Optional.empty();
        return new WorkProjectPage(projects, nextCursor);
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

    private static WorkProjectCursor toCursor(WorkProjectEntity entity) {
        return new WorkProjectCursor(
                io.crewscope.domain.shared.time.UtcTimestamp.from(entity.updatedAt()),
                new WorkProjectId(entity.id()));
    }
}
