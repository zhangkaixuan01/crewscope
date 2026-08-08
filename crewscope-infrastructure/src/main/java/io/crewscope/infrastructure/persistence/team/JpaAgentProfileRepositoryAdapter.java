package io.crewscope.infrastructure.persistence.team;

import static io.crewscope.infrastructure.persistence.team.JpaTeamRepositoryAdapter.previousVersion;

import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.team.DefaultPersonalAgentRepository;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.team.TeamMemberStatus;
import io.crewscope.domain.workspace.AgentProfile;
import io.crewscope.domain.workspace.AgentProfileId;
import io.crewscope.domain.workspace.PersonalAgentInitialization;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;

/** Atomic JPA adapter for AgentProfile lifecycle and default Personal Agent initialization. */
@Repository
public class JpaAgentProfileRepositoryAdapter
        implements AgentProfileRepository, DefaultPersonalAgentRepository {
    private final TeamPersistenceMapper mapper;
    @PersistenceContext private EntityManager entityManager;

    public JpaAgentProfileRepositoryAdapter(TeamPersistenceMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    @Transactional
    public PersonalAgentInitialization initializeIfAbsent(PersonalAgentInitialization candidate) {
        PersonalAgentInitialization required = Objects.requireNonNull(candidate, "candidate");
        AgentProfile profile = required.agentProfile();
        TeamMemberId ownerMemberId = profile.ownerMemberId().orElseThrow();

        // The stable TeamMember row is the serialization point for retry and concurrent requests.
        TeamMemberEntity lockedMember =
                entityManager
                        .createQuery(
                                """
                                SELECT member FROM TeamMemberEntity member
                                WHERE member.organizationId = :organizationId
                                  AND member.teamId = :teamId AND member.id = :memberId
                                """,
                                TeamMemberEntity.class)
                        .setParameter("organizationId", profile.scope().organizationId().value())
                        .setParameter("teamId", profile.scope().teamId().orElseThrow().value())
                        .setParameter("memberId", ownerMemberId.value())
                        .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                        .getResultStream()
                        .findFirst()
                        .orElseThrow(
                                () -> new AggregateNotFoundException("TeamMember", ownerMemberId));
        if (!TeamMemberStatus.ACTIVE.name().equals(lockedMember.status())
                || !lockedMember
                        .userPrincipalId()
                        .equals(
                                required.agentPrincipal()
                                        .ownerPrincipalId()
                                        .orElseThrow()
                                        .value())) {
            throw new DomainValidationException(
                    "personalAgentInitialization.ownerMemberId",
                    "must reference the current active membership of the owner USER Principal");
        }

        Optional<AgentProfileEntity> existing =
                findDefaultEntity(profile.scope().organizationId(), ownerMemberId);
        if (existing.isPresent()) {
            AgentProfile committedProfile = mapper.toDomain(existing.orElseThrow());
            PrincipalEntity principal =
                    findPrincipal(
                                    profile.scope().organizationId(),
                                    committedProfile.agentPrincipalId().value())
                            .orElseThrow(
                                    () ->
                                            new AggregateNotFoundException(
                                                    "Principal",
                                                    committedProfile.agentPrincipalId()));
            return new PersonalAgentInitialization(mapper.toDomain(principal), committedProfile);
        }

        entityManager.persist(mapper.toEntity(required.agentPrincipal()));
        entityManager.persist(mapper.toEntity(profile));
        entityManager.flush();
        return required;
    }

    @Override
    @Transactional
    public AgentProfile update(AgentProfile value) {
        AgentProfile required = Objects.requireNonNull(value, "profile");
        long expected = previousVersion(required.version(), "agentProfile.version");
        int affected =
                entityManager
                        .createQuery(
                                """
                                UPDATE AgentProfileEntity value SET value.defaultProfile = :defaultProfile,
                                    value.status = :status, value.updatedAt = :updatedAt,
                                    value.updatedBy = :updatedBy, value.version = :version
                                WHERE value.organizationId = :organizationId AND value.teamId = :teamId
                                  AND value.workspaceId = :workspaceId
                                  AND value.id = :id AND value.version = :expected
                                """)
                        .setParameter("defaultProfile", required.defaultProfile())
                        .setParameter("status", required.status().name())
                        .setParameter("updatedAt", required.audit().updatedAt().value())
                        .setParameter(
                                "updatedBy", required.audit().updatedBy().orElseThrow().value())
                        .setParameter("version", required.version())
                        .setParameter("organizationId", required.scope().organizationId().value())
                        .setParameter("teamId", required.scope().teamId().orElseThrow().value())
                        .setParameter("workspaceId", required.workspaceId().value())
                        .setParameter("id", required.id().value())
                        .setParameter("expected", expected)
                        .executeUpdate();
        entityManager.clear();
        verify(affected, required, expected);
        return findById(required.scope().organizationId(), required.id()).orElseThrow();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AgentProfile> findById(OrganizationId organizationId, AgentProfileId id) {
        return entityManager
                .createQuery(
                        """
                        SELECT value FROM AgentProfileEntity value
                        WHERE value.organizationId = :organizationId AND value.id = :id
                        """,
                        AgentProfileEntity.class)
                .setParameter("organizationId", Objects.requireNonNull(organizationId).value())
                .setParameter("id", Objects.requireNonNull(id).value())
                .getResultStream()
                .findFirst()
                .map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AgentProfile> findActiveDefaultPersonal(
            OrganizationId organizationId, TeamMemberId ownerMemberId) {
        return findDefaultEntity(
                        Objects.requireNonNull(organizationId),
                        Objects.requireNonNull(ownerMemberId))
                .map(mapper::toDomain);
    }

    private Optional<AgentProfileEntity> findDefaultEntity(
            OrganizationId organizationId, TeamMemberId ownerMemberId) {
        return entityManager
                .createQuery(
                        """
                        SELECT value FROM AgentProfileEntity value
                        WHERE value.organizationId = :organizationId
                          AND value.ownerMemberId = :ownerMemberId
                          AND value.type = 'PERSONAL' AND value.defaultProfile = true
                          AND value.status = 'ACTIVE'
                        """,
                        AgentProfileEntity.class)
                .setParameter("organizationId", organizationId.value())
                .setParameter("ownerMemberId", ownerMemberId.value())
                .getResultStream()
                .findFirst();
    }

    private Optional<PrincipalEntity> findPrincipal(
            OrganizationId organizationId, java.util.UUID id) {
        return entityManager
                .createQuery(
                        """
                        SELECT value FROM PrincipalEntity value
                        WHERE value.organizationId = :organizationId AND value.id = :id
                        """,
                        PrincipalEntity.class)
                .setParameter("organizationId", organizationId.value())
                .setParameter("id", id)
                .getResultStream()
                .findFirst();
    }

    private void verify(int affected, AgentProfile value, long expected) {
        if (affected != 0) {
            return;
        }
        Optional<Long> actual =
                entityManager
                        .createQuery(
                                """
                                SELECT value.version FROM AgentProfileEntity value
                                WHERE value.organizationId = :organizationId AND value.teamId = :teamId
                                  AND value.workspaceId = :workspaceId AND value.id = :id
                                """,
                                Long.class)
                        .setParameter("organizationId", value.scope().organizationId().value())
                        .setParameter("teamId", value.scope().teamId().orElseThrow().value())
                        .setParameter("workspaceId", value.workspaceId().value())
                        .setParameter("id", value.id().value())
                        .getResultStream()
                        .findFirst();
        if (actual.isEmpty()) {
            throw new AggregateNotFoundException("AgentProfile", value.id());
        }
        throw new OptimisticLockConflictException(
                "AgentProfile", value.id(), expected, actual.orElseThrow());
    }
}
