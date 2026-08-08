package io.crewscope.infrastructure.persistence.team;

import static io.crewscope.infrastructure.persistence.team.JpaTeamRepositoryAdapter.previousVersion;

import io.crewscope.application.team.WorkspaceRepository;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.workspace.Workspace;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;

/** JPA Workspace adapter with explicit tenant and previous-version predicates. */
@Repository
public class JpaWorkspaceRepositoryAdapter implements WorkspaceRepository {
    private final TeamPersistenceMapper mapper;
    @PersistenceContext private EntityManager entityManager;

    public JpaWorkspaceRepositoryAdapter(TeamPersistenceMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    @Transactional
    public Workspace create(Workspace value) {
        Workspace required = Objects.requireNonNull(value, "workspace");
        if (required.version() != 0) {
            throw new DomainValidationException("workspace.version", "must be zero when created");
        }
        entityManager.persist(mapper.toEntity(required));
        entityManager.flush();
        return required;
    }

    @Override
    @Transactional
    public Workspace update(Workspace value) {
        Workspace required = Objects.requireNonNull(value, "workspace");
        long expected = previousVersion(required.version(), "workspace.version");
        var update =
                entityManager
                        .createQuery(
                                """
                                UPDATE WorkspaceEntity value
                                SET value.ownerPrincipalId = :owner, value.name = :name, value.status = :status,
                                    value.updatedAt = :updatedAt, value.updatedBy = :updatedBy,
                                    value.version = :version
                                WHERE value.organizationId = :organizationId
                                  AND %s AND value.id = :id AND value.version = :expected
                                """
                                        .formatted(teamScopePredicate(required)))
                        .setParameter(
                                "owner",
                                required.ownerPrincipalId().map(id -> id.value()).orElse(null))
                        .setParameter("name", required.name())
                        .setParameter("status", required.status().name())
                        .setParameter("updatedAt", required.audit().updatedAt().value())
                        .setParameter(
                                "updatedBy", required.audit().updatedBy().orElseThrow().value())
                        .setParameter("version", required.version())
                        .setParameter("organizationId", required.scope().organizationId().value())
                        .setParameter("id", required.id().value())
                        .setParameter("expected", expected);
        required.scope()
                .teamId()
                .ifPresent(teamId -> update.setParameter("teamId", teamId.value()));
        int affected = update.executeUpdate();
        entityManager.clear();
        verify(affected, required, expected);
        return findById(required.scope().organizationId(), required.id()).orElseThrow();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Workspace> findById(OrganizationId organizationId, WorkspaceId id) {
        return entityManager
                .createQuery(
                        """
                        SELECT value FROM WorkspaceEntity value
                        WHERE value.organizationId = :organizationId AND value.id = :id
                        """,
                        WorkspaceEntity.class)
                .setParameter("organizationId", Objects.requireNonNull(organizationId).value())
                .setParameter("id", Objects.requireNonNull(id).value())
                .getResultStream()
                .findFirst()
                .map(mapper::toDomain);
    }

    private void verify(int affected, Workspace value, long expected) {
        if (affected != 0) {
            return;
        }
        var versionQuery =
                entityManager
                        .createQuery(
                                """
                                SELECT value.version FROM WorkspaceEntity value
                                WHERE value.organizationId = :organizationId
                                  AND %s AND value.id = :id
                                """
                                        .formatted(teamScopePredicate(value)),
                                Long.class)
                        .setParameter("organizationId", value.scope().organizationId().value())
                        .setParameter("id", value.id().value());
        value.scope()
                .teamId()
                .ifPresent(teamId -> versionQuery.setParameter("teamId", teamId.value()));
        Optional<Long> actual = versionQuery.getResultStream().findFirst();
        if (actual.isEmpty()) {
            throw new AggregateNotFoundException("Workspace", value.id());
        }
        throw new OptimisticLockConflictException(
                "Workspace", value.id(), expected, actual.orElseThrow());
    }

    private static String teamScopePredicate(Workspace value) {
        return value.scope().teamId().isPresent()
                ? "value.teamId = :teamId"
                : "value.teamId IS NULL";
    }
}
