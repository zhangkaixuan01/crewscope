package io.crewscope.application.execution;

import io.crewscope.application.conversation.TaskIntentV1;
import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.UUID;

/** Bean-validated TaskIntent model output awaiting current-fact and domain validation. */
public record TaskIntentOutputCandidate(
        RuntimeInvocationId invocationId,
        UUID segmentId,
        ConversationId conversationId,
        TaskIntentV1 output,
        UtcTimestamp occurredAt) {

    public TaskIntentOutputCandidate {
        invocationId = Objects.requireNonNull(invocationId, "invocationId");
        segmentId = Objects.requireNonNull(segmentId, "segmentId");
        conversationId = Objects.requireNonNull(conversationId, "conversationId");
        output = Objects.requireNonNull(output, "output");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
