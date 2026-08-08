package io.crewscope.domain.conversation;

import io.crewscope.domain.shared.id.AggregateId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.team.TeamMemberId;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** Stable identifier for one Personal Agent runtime binding in one Conversation. */
public record AgentRuntimeSessionId(UUID value) implements AggregateId {

    private static final String PERSONAL_CONVERSATION_NAMESPACE =
            "io.crewscope/agent-runtime-session/personal-conversation/v1/";

    public AgentRuntimeSessionId {
        value = AggregateId.requireValue(value, "AgentRuntimeSessionId");
    }

    /** Derives the same candidate for retries of the same trusted Conversation binding. */
    public static AgentRuntimeSessionId forPersonalConversation(
            ConversationId conversationId,
            TeamMemberId ownerMemberId,
            PrincipalId personalAgentPrincipalId) {
        String source = PERSONAL_CONVERSATION_NAMESPACE
                + Objects.requireNonNull(conversationId, "conversationId")
                + "/"
                + Objects.requireNonNull(ownerMemberId, "ownerMemberId")
                + "/"
                + Objects.requireNonNull(personalAgentPrincipalId, "personalAgentPrincipalId");
        return new AgentRuntimeSessionId(
                UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8)));
    }

    public static AgentRuntimeSessionId from(String value) {
        return new AgentRuntimeSessionId(
                AggregateId.parseCanonical(value, "AgentRuntimeSessionId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
