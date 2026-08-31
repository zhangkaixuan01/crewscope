package io.crewscope.infrastructure.persistence.team;

import static io.crewscope.infrastructure.persistence.team.JpaTeamRepositoryAdapter.previousVersion;

import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.team.DefaultPersonalAgentRepository;
import io.crewscope.application.teamobserver.DefaultTeamObserverRepository;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.team.TeamMemberStatus;
import io.crewscope.domain.teamobserver.TeamObserverInitialization;
import io.crewscope.domain.teamobserver.TeamObserverTemplate;
import io.crewscope.domain.workspace.AgentProfile;
import io.crewscope.domain.workspace.AgentProfileId;
import io.crewscope.domain.workspace.PersonalAgentInitialization;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Atomic JPA adapter for AgentProfile lifecycle and default Personal Agent initialization. */
@Repository
public class JpaAgentProfileRepositoryAdapter
        implements AgentProfileRepository,
                DefaultPersonalAgentRepository,
                DefaultTeamObserverRepository {
    private final TeamPersistenceMapper mapper;
    @PersistenceContext private EntityManager entityManager;

    public JpaAgentProfileRepositoryAdapter(TeamPersistenceMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    @Transactional
    public AgentProfile create(AgentProfile profile) {
        AgentProfile required = Objects.requireNonNull(profile, "profile");
        if (required.version() != 0 || required.defaultProfile()) {
            throw new DomainValidationException(
                    "agentProfile", "create accepts only a new non-default template instance");
        }
        entityManager.persist(mapper.toEntity(required));
        entityManager.flush();
        return findById(required.scope().organizationId(), required.id()).orElseThrow();
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
    public TeamObserverInitialization initializeIfAbsent(
            TeamObserverInitialization candidate) {
        TeamObserverInitialization required = Objects.requireNonNull(candidate, "candidate");
        AgentProfile profile = required.agentProfile();
        TeamId teamId = profile.scope().teamId().orElseThrow();

        // Team is the stable serialization point for retry, startup repair and first invocation.
        entityManager
                .createQuery(
                        """
                        SELECT team FROM TeamEntity team
                        WHERE team.organizationId = :organizationId AND team.id = :teamId
                        """,
                        TeamEntity.class)
                .setParameter("organizationId", profile.scope().organizationId().value())
                .setParameter("teamId", teamId.value())
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getResultStream()
                .findFirst()
                .orElseThrow(() -> new AggregateNotFoundException("Team", teamId));

        Optional<TeamObserverInitialization> existing =
                findByTeam(profile.scope().organizationId(), teamId);
        if (existing.isPresent()) {
            return existing.orElseThrow();
        }
        entityManager.persist(mapper.toEntity(required.agentPrincipal()));
        entityManager.persist(mapper.toEntity(profile));
        entityManager.flush();
        return findByTeam(profile.scope().organizationId(), teamId).orElseThrow();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TeamObserverInitialization> findByTeam(
            OrganizationId organizationId, TeamId teamId) {
        Optional<AgentProfileEntity> profile = entityManager
                .createQuery(
                        """
                        SELECT value FROM AgentProfileEntity value
                        WHERE value.organizationId = :organizationId
                          AND value.teamId = :teamId
                          AND value.templateKey = :templateKey
                          AND value.templateVersion = :templateVersion
                        """,
                        AgentProfileEntity.class)
                .setParameter("organizationId", Objects.requireNonNull(organizationId).value())
                .setParameter("teamId", Objects.requireNonNull(teamId).value())
                .setParameter("templateKey", TeamObserverTemplate.VERSION.key().value())
                .setParameter("templateVersion", TeamObserverTemplate.VERSION.version())
                .getResultStream()
                .findFirst();
        if (profile.isEmpty()) {
            return Optional.empty();
        }
        AgentProfile committedProfile = mapper.toDomain(profile.orElseThrow());
        PrincipalEntity principal = findPrincipal(
                        organizationId, committedProfile.agentPrincipalId().value())
                .orElseThrow(() -> new AggregateNotFoundException(
                        "Principal", committedProfile.agentPrincipalId()));
        return Optional.of(new TeamObserverInitialization(
                mapper.toDomain(principal), committedProfile));
    }

    @Override
    @Transactional
    public TeamObserverInitialization updateLifecycle(
            TeamObserverInitialization initialization) {
        TeamObserverInitialization required =
                Objects.requireNonNull(initialization, "initialization");
        var principal = required.agentPrincipal();
        long expectedPrincipal = previousVersion(
                principal.version(), "teamObserver.agentPrincipal.version");
        int principalAffected = entityManager
                .createQuery(
                        """
                        UPDATE PrincipalEntity value
                           SET value.status = :status,
                               value.updatedAt = :updatedAt,
                               value.version = :version
                         WHERE value.organizationId = :organizationId
                           AND value.id = :id
                           AND value.version = :expected
                        """)
                .setParameter("status", principal.status().name())
                .setParameter("updatedAt", principal.lifecycle().updatedAt().value())
                .setParameter("version", principal.version())
                .setParameter("organizationId", principal.scope().organizationId().value())
                .setParameter("id", principal.id().value())
                .setParameter("expected", expectedPrincipal)
                .executeUpdate();
        verifyPrincipalLifecycleUpdate(principalAffected, principal, expectedPrincipal);
        update(required.agentProfile());
        entityManager.clear();
        return findByTeam(
                        required.agentProfile().scope().organizationId(),
                        required.agentProfile().scope().teamId().orElseThrow())
                .orElseThrow();
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

    @Override
    @Transactional(readOnly = true)
    public Optional<AgentProfile> findActiveByAgentPrincipalId(
            OrganizationId organizationId, PrincipalId agentPrincipalId) {
        return entityManager
                .createQuery(
                        """
                        SELECT value FROM AgentProfileEntity value
                        WHERE value.organizationId = :organizationId
                          AND value.agentPrincipalId = :agentPrincipalId
                          AND value.status = 'ACTIVE'
                        """,
                        AgentProfileEntity.class)
                .setParameter("organizationId", Objects.requireNonNull(organizationId).value())
                .setParameter("agentPrincipalId", Objects.requireNonNull(agentPrincipalId).value())
                .getResultStream()
                .findFirst()
                .map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgentProfile> findPage(
            OrganizationId organizationId, int offset, int limit) {
        requirePage(offset, limit);
        return entityManager
                .createQuery(
                        """
                        SELECT value FROM AgentProfileEntity value
                        WHERE value.organizationId = :organizationId
                        ORDER BY value.updatedAt DESC, value.id DESC
                        """,
                        AgentProfileEntity.class)
                .setParameter("organizationId", Objects.requireNonNull(organizationId).value())
                .setFirstResult(offset)
                .setMaxResults(limit)
                .getResultList().stream()
                .map(mapper::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgentProfile> findVisibleToMember(
            OrganizationId organizationId,
            TeamId teamId,
            TeamMemberId memberId,
            int offset,
            int limit) {
        requirePage(offset, limit);
        return entityManager
                .createQuery(
                        """
                        SELECT value FROM AgentProfileEntity value
                        WHERE value.organizationId = :organizationId AND value.teamId = :teamId
                          AND (value.ownerMemberId = :memberId OR value.ownershipType = 'TEAM')
                        ORDER BY value.updatedAt DESC, value.id DESC
                        """,
                        AgentProfileEntity.class)
                .setParameter("organizationId", Objects.requireNonNull(organizationId).value())
                .setParameter("teamId", Objects.requireNonNull(teamId).value())
                .setParameter("memberId", Objects.requireNonNull(memberId).value())
                .setFirstResult(offset)
                .setMaxResults(limit)
                .getResultList().stream()
                .map(mapper::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgentProfile> findByTeam(
            OrganizationId organizationId, TeamId teamId, int offset, int limit) {
        requirePage(offset, limit);
        return entityManager
                .createQuery(
                        """
                        SELECT value FROM AgentProfileEntity value
                        WHERE value.organizationId = :organizationId AND value.teamId = :teamId
                        ORDER BY value.updatedAt DESC, value.id DESC
                        """,
                        AgentProfileEntity.class)
                .setParameter("organizationId", Objects.requireNonNull(organizationId).value())
                .setParameter("teamId", Objects.requireNonNull(teamId).value())
                .setFirstResult(offset)
                .setMaxResults(limit)
                .getResultList().stream()
                .map(mapper::toDomain)
                .toList();
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

    private void verifyPrincipalLifecycleUpdate(
            int affected,
            io.crewscope.domain.identity.Principal principal,
            long expectedVersion) {
        if (affected != 0) {
            return;
        }
        Optional<Long> actual = entityManager
                .createQuery(
                        """
                        SELECT value.version FROM PrincipalEntity value
                        WHERE value.organizationId = :organizationId AND value.id = :id
                        """,
                        Long.class)
                .setParameter("organizationId", principal.scope().organizationId().value())
                .setParameter("id", principal.id().value())
                .getResultStream()
                .findFirst();
        if (actual.isEmpty()) {
            throw new AggregateNotFoundException("Principal", principal.id());
        }
        throw new OptimisticLockConflictException(
                "Principal", principal.id(), expectedVersion, actual.orElseThrow());
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

    private static void requirePage(int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 200) {
            throw new DomainValidationException(
                    "agentProfile.page", "offset must be non-negative and limit between 1 and 200");
        }
    }
}
