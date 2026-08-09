package io.crewscope.application.execution;

import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.conversation.ConversationParticipantId;
import io.crewscope.domain.conversation.MessageContent;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.UUID;

/**
 * Validated assistant content awaiting a Conversation transaction and server-assigned sequence.
 *
 * <p>This is not a committed Message and therefore cannot be published as a business fact.
 */
public record AgentMessageCandidate(
        RuntimeInvocationId invocationId,
        UUID segmentId,
        ConversationId conversationId,
        ConversationParticipantId participantId,
        PrincipalId authorPrincipalId,
        MessageContent content,
        UtcTimestamp occurredAt) {

    public AgentMessageCandidate {
        invocationId = Objects.requireNonNull(invocationId, "invocationId");
        segmentId = Objects.requireNonNull(segmentId, "segmentId");
        conversationId = Objects.requireNonNull(conversationId, "conversationId");
        participantId = Objects.requireNonNull(participantId, "participantId");
        authorPrincipalId = Objects.requireNonNull(authorPrincipalId, "authorPrincipalId");
        content = Objects.requireNonNull(content, "content");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
