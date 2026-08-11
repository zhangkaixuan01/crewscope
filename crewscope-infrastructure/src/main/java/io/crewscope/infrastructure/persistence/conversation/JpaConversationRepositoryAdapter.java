package io.crewscope.infrastructure.persistence.conversation;

import io.crewscope.application.conversation.ConversationListCursor;
import io.crewscope.application.conversation.ConversationPage;
import io.crewscope.application.conversation.ConversationParticipantRepository;
import io.crewscope.application.conversation.ConversationQuery;
import io.crewscope.application.conversation.ConversationRepository;
import io.crewscope.application.conversation.ConversationWorkItemLinkRepository;
import io.crewscope.application.conversation.MessageHistoryQuery;
import io.crewscope.application.conversation.MessagePage;
import io.crewscope.application.conversation.MessageRepository;
import io.crewscope.application.conversation.TaskIntentRepository;
import io.crewscope.domain.conversation.Conversation;
import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.conversation.ConversationParticipant;
import io.crewscope.domain.conversation.ConversationParticipantId;
import io.crewscope.domain.conversation.ConversationScope;
import io.crewscope.domain.conversation.ConversationWorkItemLink;
import io.crewscope.domain.conversation.Message;
import io.crewscope.domain.conversation.MessageId;
import io.crewscope.domain.conversation.TaskIntent;
import io.crewscope.domain.conversation.TaskIntentId;
import io.crewscope.domain.conversation.TaskIntentStatus;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.AggregateId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.workitem.WorkItemId;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** JPA adapter for Conversation facts, explicit row locks and stable keyset reads. */
@Repository
public class JpaConversationRepositoryAdapter
        implements ConversationRepository,
                ConversationParticipantRepository,
                MessageRepository,
                TaskIntentRepository,
                ConversationWorkItemLinkRepository {

    private final EntityManager entityManager;
    private final ConversationPersistenceMapper mapper;

    public JpaConversationRepositoryAdapter(
            EntityManager entityManager, ConversationPersistenceMapper mapper) {
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    @Transactional
    public Conversation create(Conversation conversation) {
        Conversation required = Objects.requireNonNull(conversation, "conversation");
        requireNew("conversation.version", required.version());
        ConversationEntity row = mapper.toEntity(required);
        entityManager.persist(row);
        entityManager.flush();
        return mapper.toDomain(row);
    }

    @Override
    @Transactional
    public Conversation update(Conversation conversation) {
        Conversation required = Objects.requireNonNull(conversation, "conversation");
        ConversationEntity row = mapper.toEntity(required);
        long expectedVersion = expectedVersion("conversation.version", required.version());
        int affected = entityManager
                .createQuery(
                        """
                        UPDATE ConversationEntity value
                        SET value.title = :title,
                            value.visibility = :visibility,
                            value.status = :status,
                            value.lastMessageSequence = :lastMessageSequence,
                            value.updatedAt = :updatedAt,
                            value.updatedByPrincipalId = :updatedBy,
                            value.version = :version
                        WHERE value.organizationId = :organizationId
                          AND value.teamId = :teamId
                          AND value.workspaceId = :workspaceId
                          AND value.id = :id
                          AND value.version = :expectedVersion
                        """)
                .setParameter("title", row.title)
                .setParameter("visibility", row.visibility)
                .setParameter("status", row.status)
                .setParameter("lastMessageSequence", row.lastMessageSequence)
                .setParameter("updatedAt", row.updatedAt)
                .setParameter("updatedBy", row.updatedByPrincipalId)
                .setParameter("version", row.version)
                .setParameter("organizationId", row.organizationId)
                .setParameter("teamId", row.teamId)
                .setParameter("workspaceId", row.workspaceId)
                .setParameter("id", row.id)
                .setParameter("expectedVersion", expectedVersion)
                .executeUpdate();
        finishUpdate(
                "Conversation",
                required.id(),
                expectedVersion,
                affected,
                ConversationEntity.class,
                row.organizationId,
                row.teamId,
                row.workspaceId);
        return findById(required.scope().organizationId(), required.id())
                .orElseThrow(() -> new AggregateNotFoundException("Conversation", required.id()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Conversation> findById(OrganizationId organizationId, ConversationId id) {
        return findConversation(organizationId, id, LockModeType.NONE).map(mapper::toDomain);
    }

    @Override
    @Transactional
    public Optional<Conversation> lockById(OrganizationId organizationId, ConversationId id) {
        // Message sequence allocation must happen only after acquiring this row lock.
        return findConversation(organizationId, id, LockModeType.PESSIMISTIC_WRITE)
                .map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public ConversationPage findPage(ConversationQuery query) {
        ConversationQuery required = Objects.requireNonNull(query, "query");
        StringBuilder jpql = new StringBuilder(
                """
                SELECT value FROM ConversationEntity value
                WHERE value.organizationId = :organizationId
                  AND value.teamId = :teamId
                  AND (value.visibility = 'TEAM' OR EXISTS (
                      SELECT participant.id FROM ConversationParticipantEntity participant
                      WHERE participant.organizationId = value.organizationId
                        AND participant.teamId = value.teamId
                        AND participant.workspaceId = value.workspaceId
                        AND participant.conversationId = value.id
                        AND participant.principalId = :viewerPrincipalId
                  ))
                """);
        required.ownerMemberId().ifPresent(ignored -> jpql.append(" AND value.ownerMemberId = :ownerMemberId"));
        required.status().ifPresent(ignored -> jpql.append(" AND value.status = :status"));
        required.cursor().ifPresent(ignored -> jpql.append(
                """
                 AND (value.updatedAt < :cursorTime
                      OR (value.updatedAt = :cursorTime AND value.id < :cursorId))
                """));
        jpql.append(" ORDER BY value.updatedAt DESC, value.id DESC");

        var persistenceQuery = entityManager
                .createQuery(jpql.toString(), ConversationEntity.class)
                .setParameter("organizationId", required.organizationId().value())
                .setParameter("teamId", required.teamId().value())
                .setParameter("viewerPrincipalId", required.viewerPrincipalId().value())
                .setMaxResults(required.limit() + 1);
        required.ownerMemberId().ifPresent(value -> persistenceQuery.setParameter("ownerMemberId", value.value()));
        required.status().ifPresent(value -> persistenceQuery.setParameter("status", value.name()));
        required.cursor().ifPresent(value -> {
            persistenceQuery.setParameter("cursorTime", value.updatedAt().value());
            persistenceQuery.setParameter("cursorId", value.id().value());
        });
        List<ConversationEntity> rows = trimExtra(persistenceQuery.getResultList(), required.limit());
        boolean hasNext = rows.size() > required.limit();
        if (hasNext) {
            rows.remove(rows.size() - 1);
        }
        List<Conversation> values = rows.stream().map(mapper::toDomain).toList();
        Optional<ConversationListCursor> nextCursor = hasNext
                ? Optional.of(ConversationListCursor.from(values.get(values.size() - 1)))
                : Optional.empty();
        return new ConversationPage(values, nextCursor);
    }

    @Override
    @Transactional
    public ConversationParticipant create(ConversationParticipant participant) {
        ConversationParticipant required = Objects.requireNonNull(participant, "participant");
        requireNew("conversationParticipant.version", required.version());
        ConversationParticipantEntity row = mapper.toEntity(required);
        entityManager.persist(row);
        entityManager.flush();
        return mapper.toDomain(row);
    }

    @Override
    @Transactional
    public ConversationParticipant update(ConversationParticipant participant) {
        ConversationParticipant required = Objects.requireNonNull(participant, "participant");
        ConversationParticipantEntity row = mapper.toEntity(required);
        long expectedVersion = expectedVersion("conversationParticipant.version", required.version());
        int affected = entityManager
                .createQuery(
                        """
                        UPDATE ConversationParticipantEntity value
                        SET value.status = :status,
                            value.leftAt = :leftAt,
                            value.updatedAt = :updatedAt,
                            value.updatedByPrincipalId = :updatedBy,
                            value.version = :version
                        WHERE value.organizationId = :organizationId
                          AND value.teamId = :teamId
                          AND value.workspaceId = :workspaceId
                          AND value.conversationId = :conversationId
                          AND value.id = :id
                          AND value.version = :expectedVersion
                        """)
                .setParameter("status", row.status)
                .setParameter("leftAt", row.leftAt)
                .setParameter("updatedAt", row.updatedAt)
                .setParameter("updatedBy", row.updatedByPrincipalId)
                .setParameter("version", row.version)
                .setParameter("organizationId", row.organizationId)
                .setParameter("teamId", row.teamId)
                .setParameter("workspaceId", row.workspaceId)
                .setParameter("conversationId", row.conversationId)
                .setParameter("id", row.id)
                .setParameter("expectedVersion", expectedVersion)
                .executeUpdate();
        finishUpdate(
                "ConversationParticipant",
                required.id(),
                expectedVersion,
                affected,
                ConversationParticipantEntity.class,
                row.organizationId,
                row.teamId,
                row.workspaceId);
        return findById(required.scope().organizationId(), required.id())
                .orElseThrow(() -> new AggregateNotFoundException("ConversationParticipant", required.id()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ConversationParticipant> findById(
            OrganizationId organizationId, ConversationParticipantId id) {
        return entityManager
                .createQuery(
                        "SELECT value FROM ConversationParticipantEntity value WHERE value.organizationId = :organizationId AND value.id = :id",
                        ConversationParticipantEntity.class)
                .setParameter("organizationId", Objects.requireNonNull(organizationId).value())
                .setParameter("id", Objects.requireNonNull(id).value())
                .getResultStream()
                .findFirst()
                .map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConversationParticipant> findByConversation(
            OrganizationId organizationId, ConversationId conversationId) {
        return entityManager
                .createQuery(
                        """
                        SELECT value FROM ConversationParticipantEntity value
                        WHERE value.organizationId = :organizationId
                          AND value.conversationId = :conversationId
                        ORDER BY value.joinedAt, value.id
                        """,
                        ConversationParticipantEntity.class)
                .setParameter("organizationId", Objects.requireNonNull(organizationId).value())
                .setParameter("conversationId", Objects.requireNonNull(conversationId).value())
                .getResultList()
                .stream()
                .map(mapper::toDomain)
                .toList();
    }

    @Override
    @Transactional
    public Message create(Message message, Optional<String> clientMessageKey) {
        Message required = Objects.requireNonNull(message, "message");
        Optional<String> normalizedKey = normalizeClientKey(clientMessageKey);
        Optional<Message> existing = normalizedKey.flatMap(key -> findByClientMessageKey(
                required.scope().organizationId(), required.conversationId(), key));
        if (existing.isPresent()) {
            if (!existing.orElseThrow().id().equals(required.id())) {
                throw new DomainValidationException(
                        "message.clientMessageKey", "is already associated with another Message");
            }
            return existing.orElseThrow();
        }
        MessageEntity row = mapper.toEntity(required, normalizedKey);
        entityManager.persist(row);
        entityManager.flush();
        return mapper.toDomain(row);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Message> findById(OrganizationId organizationId, MessageId id) {
        return entityManager
                .createQuery(
                        "SELECT value FROM MessageEntity value WHERE value.organizationId = :organizationId AND value.id = :id AND value.moderationStatus = 'VISIBLE'",
                        MessageEntity.class)
                .setParameter("organizationId", Objects.requireNonNull(organizationId).value())
                .setParameter("id", Objects.requireNonNull(id).value())
                .getResultStream()
                .findFirst()
                .map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Message> findByClientMessageKey(
            OrganizationId organizationId,
            ConversationId conversationId,
            String clientMessageKey) {
        String requiredKey = normalizeClientKey(Optional.ofNullable(clientMessageKey)).orElseThrow();
        return entityManager
                .createQuery(
                        """
                        SELECT value FROM MessageEntity value
                        WHERE value.organizationId = :organizationId
                          AND value.conversationId = :conversationId
                          AND value.clientMessageKey = :clientMessageKey
                          AND value.moderationStatus = 'VISIBLE'
                        """,
                        MessageEntity.class)
                .setParameter("organizationId", Objects.requireNonNull(organizationId).value())
                .setParameter("conversationId", Objects.requireNonNull(conversationId).value())
                .setParameter("clientMessageKey", requiredKey)
                .getResultStream()
                .findFirst()
                .map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public MessagePage findPage(MessageHistoryQuery query) {
        MessageHistoryQuery required = Objects.requireNonNull(query, "query");
        StringBuilder jpql = new StringBuilder(
                """
                SELECT value FROM MessageEntity value
                WHERE value.organizationId = :organizationId
                  AND value.teamId = :teamId
                  AND value.workspaceId = :workspaceId
                  AND value.conversationId = :conversationId
                  AND value.moderationStatus = 'VISIBLE'
                """);
        required.visibleThrough().ifPresent(ignored -> jpql.append(" AND value.createdAt <= :visibleThrough"));
        required.cursor().ifPresent(ignored -> jpql.append(" AND value.sequence < :cursorSequence"));
        jpql.append(" ORDER BY value.sequence DESC, value.id DESC");
        var persistenceQuery = entityManager
                .createQuery(jpql.toString(), MessageEntity.class)
                .setParameter("organizationId", required.scope().organizationId().value())
                .setParameter("teamId", required.scope().teamId().value())
                .setParameter("workspaceId", required.scope().workspaceId().value())
                .setParameter("conversationId", required.conversationId().value())
                .setMaxResults(required.limit() + 1);
        required.visibleThrough().ifPresent(value -> persistenceQuery.setParameter("visibleThrough", value.value()));
        required.cursor().ifPresent(value -> persistenceQuery.setParameter("cursorSequence", value.sequence().value()));
        List<MessageEntity> rows = trimExtra(persistenceQuery.getResultList(), required.limit());
        boolean hasNext = rows.size() > required.limit();
        if (hasNext) {
            rows.remove(rows.size() - 1);
        }
        List<Message> values = rows.stream().map(mapper::toDomain).toList();
        Optional<io.crewscope.application.conversation.ConversationMessageCursor> nextCursor = hasNext
                ? Optional.of(new io.crewscope.application.conversation.ConversationMessageCursor(
                        required.conversationId(), values.get(values.size() - 1).sequence()))
                : Optional.empty();
        return new MessagePage(values, nextCursor);
    }

    @Override
    @Transactional
    public TaskIntent create(TaskIntent taskIntent) {
        TaskIntent required = Objects.requireNonNull(taskIntent, "taskIntent");
        requireNew("taskIntent.version", required.version());
        if (required.status() == TaskIntentStatus.CONFIRMED) {
            throw new DomainValidationException("taskIntent.status", "cannot be CONFIRMED when created");
        }
        TaskIntentEntity row = mapper.toEntity(required, Optional.empty());
        entityManager.persist(row);
        entityManager.flush();
        return mapper.toDomain(row);
    }

    @Override
    @Transactional
    public TaskIntent update(TaskIntent taskIntent) {
        TaskIntent required = Objects.requireNonNull(taskIntent, "taskIntent");
        if (required.status() == TaskIntentStatus.CONFIRMED) {
            throw new DomainValidationException(
                    "taskIntent.status", "use confirm to bind the resulting WorkItem atomically");
        }
        return updateTaskIntent(required, Optional.empty());
    }

    @Override
    @Transactional
    public TaskIntent confirm(TaskIntent taskIntent, WorkItemId confirmedWorkItemId) {
        TaskIntent required = Objects.requireNonNull(taskIntent, "taskIntent");
        if (required.status() != TaskIntentStatus.CONFIRMED) {
            throw new DomainValidationException("taskIntent.status", "must be CONFIRMED");
        }
        return updateTaskIntent(required, Optional.of(Objects.requireNonNull(confirmedWorkItemId)));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TaskIntent> findById(OrganizationId organizationId, TaskIntentId id) {
        return findTaskIntent(organizationId, id, LockModeType.NONE).map(mapper::toDomain);
    }

    @Override
    @Transactional
    public Optional<TaskIntent> lockById(OrganizationId organizationId, TaskIntentId id) {
        return findTaskIntent(organizationId, id, LockModeType.PESSIMISTIC_WRITE)
                .map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WorkItemId> findConfirmedWorkItemId(
            OrganizationId organizationId, TaskIntentId id) {
        return entityManager
                .createQuery(
                        "SELECT value.confirmedWorkItemId FROM TaskIntentEntity value WHERE value.organizationId = :organizationId AND value.id = :id",
                        UUID.class)
                .setParameter("organizationId", Objects.requireNonNull(organizationId).value())
                .setParameter("id", Objects.requireNonNull(id).value())
                .getResultStream()
                .findFirst()
                .map(WorkItemId::new);
    }

    @Override
    @Transactional
    public ConversationWorkItemLink create(ConversationWorkItemLink link) {
        ConversationWorkItemLinkEntity row = mapper.toEntity(Objects.requireNonNull(link, "link"));
        entityManager.persist(row);
        entityManager.flush();
        return mapper.toDomain(row);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ConversationWorkItemLink> find(
            OrganizationId organizationId, ConversationId conversationId, WorkItemId workItemId) {
        return entityManager
                .createQuery(
                        """
                        SELECT value FROM ConversationWorkItemLinkEntity value
                        WHERE value.organizationId = :organizationId
                          AND value.conversationId = :conversationId
                          AND value.workItemId = :workItemId
                        """,
                        ConversationWorkItemLinkEntity.class)
                .setParameter("organizationId", Objects.requireNonNull(organizationId).value())
                .setParameter("conversationId", Objects.requireNonNull(conversationId).value())
                .setParameter("workItemId", Objects.requireNonNull(workItemId).value())
                .getResultStream()
                .findFirst()
                .map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConversationWorkItemLink> findLinksByConversation(
            OrganizationId organizationId, ConversationId conversationId) {
        return findLinks(organizationId, "value.conversationId", Objects.requireNonNull(conversationId).value());
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConversationWorkItemLink> findLinksByWorkItem(
            OrganizationId organizationId, WorkItemId workItemId) {
        return findLinks(organizationId, "value.workItemId", Objects.requireNonNull(workItemId).value());
    }

    private TaskIntent updateTaskIntent(TaskIntent value, Optional<WorkItemId> confirmedWorkItemId) {
        TaskIntentEntity row = mapper.toEntity(value, confirmedWorkItemId);
        long expectedVersion = expectedVersion("taskIntent.version", value.version());
        int affected = entityManager
                .createQuery(
                        """
                        UPDATE TaskIntentEntity value
                        SET value.proposalRevision = :proposalRevision,
                            value.workProjectId = :workProjectId,
                            value.objective = :objective,
                            value.acceptanceCriteria = :acceptanceCriteria,
                            value.ownerPrincipalId = :ownerPrincipalId,
                            value.ownerPrincipalType = :ownerPrincipalType,
                            value.ownerMemberId = :ownerMemberId,
                            value.executorPrincipalId = :executorPrincipalId,
                            value.executorPrincipalType = :executorPrincipalType,
                            value.executorMemberId = :executorMemberId,
                            value.gateReviewerPrincipalId = :gateReviewerPrincipalId,
                            value.gateReviewerPrincipalType = :gateReviewerPrincipalType,
                            value.gateReviewerMemberId = :gateReviewerMemberId,
                            value.status = :status,
                            value.decidedByPrincipalId = :decidedByPrincipalId,
                            value.decidedAt = :decidedAt,
                            value.decisionReason = :decisionReason,
                            value.confirmedWorkItemId = :confirmedWorkItemId,
                            value.updatedAt = :updatedAt,
                            value.updatedByPrincipalId = :updatedBy,
                            value.version = :version
                        WHERE value.organizationId = :organizationId
                          AND value.teamId = :teamId
                          AND value.workspaceId = :workspaceId
                          AND value.conversationId = :conversationId
                          AND value.id = :id
                          AND value.version = :expectedVersion
                          AND (:confirmedWorkItemId IS NULL OR value.status = 'READY')
                        """)
                .setParameter("proposalRevision", row.proposalRevision)
                .setParameter("workProjectId", row.workProjectId)
                .setParameter("objective", row.objective)
                .setParameter("acceptanceCriteria", row.acceptanceCriteria)
                .setParameter("ownerPrincipalId", row.ownerPrincipalId)
                .setParameter("ownerPrincipalType", row.ownerPrincipalType)
                .setParameter("ownerMemberId", row.ownerMemberId)
                .setParameter("executorPrincipalId", row.executorPrincipalId)
                .setParameter("executorPrincipalType", row.executorPrincipalType)
                .setParameter("executorMemberId", row.executorMemberId)
                .setParameter("gateReviewerPrincipalId", row.gateReviewerPrincipalId)
                .setParameter("gateReviewerPrincipalType", row.gateReviewerPrincipalType)
                .setParameter("gateReviewerMemberId", row.gateReviewerMemberId)
                .setParameter("status", row.status)
                .setParameter("decidedByPrincipalId", row.decidedByPrincipalId)
                .setParameter("decidedAt", row.decidedAt)
                .setParameter("decisionReason", row.decisionReason)
                .setParameter("confirmedWorkItemId", row.confirmedWorkItemId)
                .setParameter("updatedAt", row.updatedAt)
                .setParameter("updatedBy", row.updatedByPrincipalId)
                .setParameter("version", row.version)
                .setParameter("organizationId", row.organizationId)
                .setParameter("teamId", row.teamId)
                .setParameter("workspaceId", row.workspaceId)
                .setParameter("conversationId", row.conversationId)
                .setParameter("id", row.id)
                .setParameter("expectedVersion", expectedVersion)
                .executeUpdate();
        finishUpdate(
                "TaskIntent",
                value.id(),
                expectedVersion,
                affected,
                TaskIntentEntity.class,
                row.organizationId,
                row.teamId,
                row.workspaceId);
        return findById(value.scope().organizationId(), value.id())
                .orElseThrow(() -> new AggregateNotFoundException("TaskIntent", value.id()));
    }

    private Optional<ConversationEntity> findConversation(
            OrganizationId organizationId, ConversationId id, LockModeType lockMode) {
        return entityManager
                .createQuery(
                        "SELECT value FROM ConversationEntity value WHERE value.organizationId = :organizationId AND value.id = :id",
                        ConversationEntity.class)
                .setParameter("organizationId", Objects.requireNonNull(organizationId).value())
                .setParameter("id", Objects.requireNonNull(id).value())
                .setLockMode(lockMode)
                .getResultStream()
                .findFirst();
    }

    private Optional<TaskIntentEntity> findTaskIntent(
            OrganizationId organizationId, TaskIntentId id, LockModeType lockMode) {
        return entityManager
                .createQuery(
                        "SELECT value FROM TaskIntentEntity value WHERE value.organizationId = :organizationId AND value.id = :id",
                        TaskIntentEntity.class)
                .setParameter("organizationId", Objects.requireNonNull(organizationId).value())
                .setParameter("id", Objects.requireNonNull(id).value())
                .setLockMode(lockMode)
                .getResultStream()
                .findFirst();
    }

    private List<ConversationWorkItemLink> findLinks(
            OrganizationId organizationId, String field, UUID value) {
        return entityManager
                .createQuery(
                        "SELECT value FROM ConversationWorkItemLinkEntity value WHERE value.organizationId = :organizationId AND "
                                + field
                                + " = :value ORDER BY value.createdAt, value.id",
                        ConversationWorkItemLinkEntity.class)
                .setParameter("organizationId", Objects.requireNonNull(organizationId).value())
                .setParameter("value", value)
                .getResultList()
                .stream()
                .map(mapper::toDomain)
                .toList();
    }

    private void finishUpdate(
            String aggregateType,
            AggregateId id,
            long expectedVersion,
            int affected,
            Class<?> entityType,
            UUID organizationId,
            UUID teamId,
            UUID workspaceId) {
        entityManager.clear();
        if (affected != 0) {
            return;
        }
        Optional<Long> actualVersion = entityManager
                .createQuery(
                        "SELECT value.version FROM "
                                + entityType.getSimpleName()
                                + " value WHERE value.organizationId = :organizationId"
                                + " AND value.teamId = :teamId"
                                + " AND value.workspaceId = :workspaceId"
                                + " AND value.id = :id",
                        Long.class)
                .setParameter("organizationId", organizationId)
                .setParameter("teamId", teamId)
                .setParameter("workspaceId", workspaceId)
                .setParameter("id", id.value())
                .getResultStream()
                .findFirst();
        if (actualVersion.isEmpty()) {
            throw new AggregateNotFoundException(aggregateType, id);
        }
        throw new OptimisticLockConflictException(
                aggregateType, id, expectedVersion, actualVersion.orElseThrow());
    }

    private static long expectedVersion(String field, long version) {
        long expected = version - 1;
        if (expected < 0) {
            throw new DomainValidationException(field, "must contain one uncommitted domain mutation");
        }
        return expected;
    }

    private static void requireNew(String field, long version) {
        if (version != 0) {
            throw new DomainValidationException(field, "must be zero when created");
        }
    }

    private static Optional<String> normalizeClientKey(Optional<String> value) {
        return Objects.requireNonNull(value, "clientMessageKey").map(key -> {
            String normalized = key.strip();
            if (normalized.isEmpty() || normalized.length() > 200) {
                throw new DomainValidationException(
                        "message.clientMessageKey", "must contain between 1 and 200 characters");
            }
            return normalized;
        });
    }

    private static <T> List<T> trimExtra(List<T> values, int limit) {
        List<T> rows = new ArrayList<>(values);
        return rows.size() <= limit + 1 ? rows : new ArrayList<>(rows.subList(0, limit + 1));
    }
}
