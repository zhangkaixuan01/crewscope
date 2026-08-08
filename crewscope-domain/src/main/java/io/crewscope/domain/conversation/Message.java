package io.crewscope.domain.conversation;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.Optional;

/** Immutable committed content authored by a participant or emitted as a system notice. */
public final class Message {

    private final MessageId id;
    private final ConversationScope scope;
    private final ConversationId conversationId;
    private final MessageSequence sequence;
    private final MessageType type;
    private final Optional<ConversationParticipantId> participantId;
    private final Optional<PrincipalId> authorPrincipalId;
    private final MessageContent content;
    private final AuditMetadata audit;

    private Message(
            MessageId id,
            ConversationScope scope,
            ConversationId conversationId,
            MessageSequence sequence,
            MessageType type,
            Optional<ConversationParticipantId> participantId,
            Optional<PrincipalId> authorPrincipalId,
            MessageContent content,
            AuditMetadata audit) {
        this.id = Objects.requireNonNull(id, "id");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.conversationId = Objects.requireNonNull(conversationId, "conversationId");
        this.sequence = Objects.requireNonNull(sequence, "sequence");
        this.type = Objects.requireNonNull(type, "type");
        this.participantId = requireAuthorShape(type, participantId, authorPrincipalId);
        this.authorPrincipalId = Objects.requireNonNull(authorPrincipalId, "authorPrincipalId");
        this.content = Objects.requireNonNull(content, "content");
        this.audit = Objects.requireNonNull(audit, "audit");
        requireCreationAudit(this.type, this.authorPrincipalId, this.audit);
    }

    /** Appends a committed USER or Agent message after validating participant authorship. */
    static Message post(
            MessageId id,
            Conversation conversation,
            ConversationParticipant participant,
            Principal author,
            MessageSequence sequence,
            MessageContent content,
            UtcTimestamp occurredAt) {
        Conversation requiredConversation = requireActiveConversation(conversation);
        ConversationParticipant requiredParticipant =
                requireActiveParticipant(requiredConversation, participant);
        Principal requiredAuthor = Objects.requireNonNull(author, "author");
        PrincipalId authorId = ConversationActorPolicy.requireActiveInScope(
                requiredAuthor,
                requiredConversation.scope(),
                "message.authorPrincipalId");
        if (!requiredParticipant.principalId().equals(authorId)) {
            throw new DomainValidationException(
                    "message.authorPrincipalId", "must match the Conversation participant");
        }
        MessageType messageType = requireMessageType(requiredParticipant, requiredAuthor);
        return new Message(
                id,
                requiredConversation.scope(),
                requiredConversation.id(),
                sequence,
                messageType,
                Optional.of(requiredParticipant.id()),
                Optional.of(authorId),
                content,
                AuditMetadata.createdBy(authorId, occurredAt));
    }

    /** Appends a system notice while preserving the trusted Principal that emitted it. */
    static Message systemNotice(
            MessageId id,
            Conversation conversation,
            Principal actor,
            MessageSequence sequence,
            MessageContent content,
            UtcTimestamp occurredAt) {
        Conversation requiredConversation = requireActiveConversation(conversation);
        PrincipalId actorId = ConversationActorPolicy.requireActiveInScope(
                actor, requiredConversation.scope(), "message.createdByPrincipalId");
        return new Message(
                id,
                requiredConversation.scope(),
                requiredConversation.id(),
                sequence,
                MessageType.SYSTEM_NOTICE,
                Optional.empty(),
                Optional.empty(),
                content,
                AuditMetadata.createdBy(actorId, occurredAt));
    }

    /** Reconstitutes immutable committed content without replaying an append operation. */
    public static Message reconstitute(
            MessageId id,
            ConversationScope scope,
            ConversationId conversationId,
            MessageSequence sequence,
            MessageType type,
            Optional<ConversationParticipantId> participantId,
            Optional<PrincipalId> authorPrincipalId,
            MessageContent content,
            AuditMetadata audit) {
        return new Message(
                id,
                scope,
                conversationId,
                sequence,
                type,
                participantId,
                authorPrincipalId,
                content,
                audit);
    }

    public MessageId id() {
        return id;
    }

    public ConversationScope scope() {
        return scope;
    }

    public ConversationId conversationId() {
        return conversationId;
    }

    public MessageSequence sequence() {
        return sequence;
    }

    public MessageType type() {
        return type;
    }

    public Optional<ConversationParticipantId> participantId() {
        return participantId;
    }

    public Optional<PrincipalId> authorPrincipalId() {
        return authorPrincipalId;
    }

    public MessageContent content() {
        return content;
    }

    public AuditMetadata audit() {
        return audit;
    }

    private static MessageType requireMessageType(
            ConversationParticipant participant, Principal author) {
        if (author.type() == PrincipalType.USER
                && participant.role() != ConversationParticipantRole.AGENT) {
            return MessageType.USER_MESSAGE;
        }
        if (author.type().isAgent()
                && participant.role() == ConversationParticipantRole.AGENT) {
            return MessageType.AGENT_MESSAGE;
        }
        throw new DomainValidationException(
                "message.authorPrincipalId",
                "Principal type must match the USER or Agent participant role");
    }

    private static Conversation requireActiveConversation(Conversation conversation) {
        Conversation requiredConversation = Objects.requireNonNull(conversation, "conversation");
        if (!requiredConversation.acceptsMessages()) {
            throw new DomainValidationException(
                    "message.conversationId", "must reference an active Conversation");
        }
        return requiredConversation;
    }

    private static ConversationParticipant requireActiveParticipant(
            Conversation conversation, ConversationParticipant participant) {
        ConversationParticipant requiredParticipant =
                Objects.requireNonNull(participant, "participant");
        if (!requiredParticipant.isActive()
                || !requiredParticipant.conversationId().equals(conversation.id())
                || !requiredParticipant.scope().equals(conversation.scope())) {
            throw new DomainValidationException(
                    "message.participantId",
                    "must reference an active participant of this Conversation");
        }
        return requiredParticipant;
    }

    private static Optional<ConversationParticipantId> requireAuthorShape(
            MessageType type,
            Optional<ConversationParticipantId> participantId,
            Optional<PrincipalId> authorPrincipalId) {
        Optional<ConversationParticipantId> requiredParticipant =
                Objects.requireNonNull(participantId, "participantId");
        Optional<PrincipalId> requiredAuthor =
                Objects.requireNonNull(authorPrincipalId, "authorPrincipalId");
        if (type == MessageType.SYSTEM_NOTICE
                && (requiredParticipant.isPresent() || requiredAuthor.isPresent())) {
            throw new DomainValidationException(
                    "message.authorPrincipalId",
                    "must be empty for a system notice");
        }
        if (type != MessageType.SYSTEM_NOTICE
                && (requiredParticipant.isEmpty() || requiredAuthor.isEmpty())) {
            throw new DomainValidationException(
                    "message.authorPrincipalId",
                    "is required with a participant for an authored message");
        }
        return requiredParticipant;
    }

    private static void requireCreationAudit(
            MessageType type,
            Optional<PrincipalId> authorPrincipalId,
            AuditMetadata audit) {
        if (type != MessageType.SYSTEM_NOTICE
                && audit.createdBy().filter(authorPrincipalId.orElseThrow()::equals).isEmpty()) {
            throw new DomainValidationException(
                    "message.audit.createdBy",
                    "must match the authored Message Principal");
        }
    }
}
