package io.crewscope.infrastructure.persistence.conversation;

import io.crewscope.application.conversation.AgentRuntimeSessionRepository;
import io.crewscope.domain.conversation.AgentRuntimeSession;
import io.crewscope.domain.conversation.AgentRuntimeSessionId;
import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Atomic, retry-stable persistence for Conversation-scoped Agent runtime sessions. */
@Repository
public class JpaAgentRuntimeSessionRepositoryAdapter implements AgentRuntimeSessionRepository {

    private final EntityManager entityManager;
    private final ConversationPersistenceMapper mapper;

    public JpaAgentRuntimeSessionRepositoryAdapter(
            EntityManager entityManager, ConversationPersistenceMapper mapper) {
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    @Transactional
    public AgentRuntimeSession initializeIfAbsent(AgentRuntimeSession candidate) {
        AgentRuntimeSession required = Objects.requireNonNull(candidate, "candidate");
        if (required.version() != 0) {
            throw new DomainValidationException(
                    "agentRuntimeSession.version", "must be zero when initialized");
        }

        // The parent Conversation is the common serialization point for all equivalent candidates.
        boolean conversationExists = entityManager
                .createQuery(
                        """
                        SELECT value FROM ConversationEntity value
                        WHERE value.organizationId = :organizationId
                          AND value.teamId = :teamId
                          AND value.workspaceId = :workspaceId
                          AND value.id = :conversationId
                        """,
                        ConversationEntity.class)
                .setParameter("organizationId", required.scope().organizationId().value())
                .setParameter("teamId", required.scope().teamId().value())
                .setParameter("workspaceId", required.scope().workspaceId().value())
                .setParameter("conversationId", required.conversationId().value())
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getResultStream()
                .findFirst()
                .isPresent();
        if (!conversationExists) {
            throw new AggregateNotFoundException("Conversation", required.conversationId());
        }

        Optional<AgentRuntimeSession> existing = findEntity(
                        required.scope().organizationId(), required.id())
                .map(mapper::toDomain);
        if (existing.isPresent()) {
            return existing.orElseThrow();
        }
        Optional<AgentRuntimeSession> active = findActiveByConversation(
                required.scope().organizationId(), required.conversationId());
        if (active.isPresent()) {
            return active.orElseThrow();
        }

        AgentRuntimeSessionEntity row = mapper.toEntity(required);
        entityManager.persist(row);
        entityManager.flush();
        return mapper.toDomain(row);
    }

    @Override
    @Transactional
    public AgentRuntimeSession update(AgentRuntimeSession session) {
        AgentRuntimeSession required = Objects.requireNonNull(session, "session");
        long expectedVersion = required.version() - 1;
        if (expectedVersion < 0) {
            throw new DomainValidationException(
                    "agentRuntimeSession.version", "must contain one uncommitted domain mutation");
        }
        AgentRuntimeSessionEntity row = mapper.toEntity(required);
        int affected = entityManager
                .createQuery(
                        """
                        UPDATE AgentRuntimeSessionEntity value
                        SET value.agentProfileVersion = :agentProfileVersion,
                            value.status = :status,
                            value.updatedAt = :updatedAt,
                            value.updatedByPrincipalId = :updatedBy,
                            value.version = :version
                        WHERE value.organizationId = :organizationId
                          AND value.teamId = :teamId
                          AND value.workspaceId = :workspaceId
                          AND value.id = :id
                          AND value.version = :expectedVersion
                        """)
                .setParameter("agentProfileVersion", row.agentProfileVersion)
                .setParameter("status", row.status)
                .setParameter("updatedAt", row.updatedAt)
                .setParameter("updatedBy", row.updatedByPrincipalId)
                .setParameter("version", row.version)
                .setParameter("organizationId", row.organizationId)
                .setParameter("teamId", row.teamId)
                .setParameter("workspaceId", row.workspaceId)
                .setParameter("id", row.id)
                .setParameter("expectedVersion", expectedVersion)
                .executeUpdate();
        entityManager.clear();
        if (affected == 0) {
            Optional<Long> actualVersion = entityManager
                    .createQuery(
                            """
                            SELECT value.version FROM AgentRuntimeSessionEntity value
                            WHERE value.organizationId = :organizationId
                              AND value.teamId = :teamId
                              AND value.workspaceId = :workspaceId
                              AND value.id = :id
                            """,
                            Long.class)
                    .setParameter("organizationId", required.scope().organizationId().value())
                    .setParameter("teamId", required.scope().teamId().value())
                    .setParameter("workspaceId", required.scope().workspaceId().value())
                    .setParameter("id", required.id().value())
                    .getResultStream()
                    .findFirst();
            if (actualVersion.isEmpty()) {
                throw new AggregateNotFoundException("AgentRuntimeSession", required.id());
            }
            throw new OptimisticLockConflictException(
                    "AgentRuntimeSession",
                    required.id(),
                    expectedVersion,
                    actualVersion.orElseThrow());
        }
        return findById(required.scope().organizationId(), required.id())
                .orElseThrow(() -> new AggregateNotFoundException("AgentRuntimeSession", required.id()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AgentRuntimeSession> findById(
            OrganizationId organizationId, AgentRuntimeSessionId id) {
        return findEntity(
                        Objects.requireNonNull(organizationId, "organizationId"),
                        Objects.requireNonNull(id, "id"))
                .map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AgentRuntimeSession> findActiveByConversation(
            OrganizationId organizationId, ConversationId conversationId) {
        return entityManager
                .createQuery(
                        """
                        SELECT value FROM AgentRuntimeSessionEntity value
                        WHERE value.organizationId = :organizationId
                          AND value.conversationId = :conversationId
                          AND value.status = 'ACTIVE'
                        """,
                        AgentRuntimeSessionEntity.class)
                .setParameter("organizationId", Objects.requireNonNull(organizationId).value())
                .setParameter("conversationId", Objects.requireNonNull(conversationId).value())
                .getResultStream()
                .findFirst()
                .map(mapper::toDomain);
    }

    private Optional<AgentRuntimeSessionEntity> findEntity(
            OrganizationId organizationId, AgentRuntimeSessionId id) {
        return entityManager
                .createQuery(
                        "SELECT value FROM AgentRuntimeSessionEntity value WHERE value.organizationId = :organizationId AND value.id = :id",
                        AgentRuntimeSessionEntity.class)
                .setParameter("organizationId", organizationId.value())
                .setParameter("id", id.value())
                .getResultStream()
                .findFirst();
    }
}
