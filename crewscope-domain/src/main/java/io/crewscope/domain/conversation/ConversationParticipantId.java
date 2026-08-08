package io.crewscope.domain.conversation;

import io.crewscope.domain.shared.id.AggregateId;
import io.crewscope.domain.shared.id.PrincipalId;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** Stable identity for one Principal participating in one Conversation. */
public record ConversationParticipantId(UUID value) implements AggregateId {

    private static final String ID_NAMESPACE = "io.crewscope/conversation-participant/";

    public ConversationParticipantId {
        value = AggregateId.requireValue(value, "ConversationParticipantId");
    }

    public static ConversationParticipantId generate() {
        return new ConversationParticipantId(AggregateId.generateValue());
    }

    /** Produces the same identifier when a participant join command is retried. */
    public static ConversationParticipantId forPrincipal(
            ConversationId conversationId, PrincipalId principalId) {
        String source = ID_NAMESPACE
                + Objects.requireNonNull(conversationId, "conversationId")
                + "/"
                + Objects.requireNonNull(principalId, "principalId");
        return new ConversationParticipantId(
                UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8)));
    }

    public static ConversationParticipantId from(String value) {
        return new ConversationParticipantId(
                AggregateId.parseCanonical(value, "ConversationParticipantId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
