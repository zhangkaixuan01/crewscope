package io.crewscope.domain.conversation;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.Optional;

/** Effective Conversation access and optional historical cutoff for one resolved subject. */
public record ConversationAccessDecision(
        ConversationId conversationId,
        boolean discoverable,
        boolean readable,
        boolean writable,
        Optional<UtcTimestamp> historyVisibleThrough) {

    public ConversationAccessDecision {
        conversationId = Objects.requireNonNull(conversationId, "conversationId");
        historyVisibleThrough = Objects.requireNonNull(
                historyVisibleThrough, "historyVisibleThrough");
        if ((writable && !readable) || (readable && !discoverable)) {
            throw new DomainValidationException(
                    "conversationAccessDecision",
                    "write requires read access and read requires discoverability");
        }
        if (historyVisibleThrough.isPresent() && (!readable || writable)) {
            throw new DomainValidationException(
                    "conversationAccessDecision.historyVisibleThrough",
                    "is only allowed for read-only historical access");
        }
    }

    public static ConversationAccessDecision denied(ConversationId conversationId) {
        return new ConversationAccessDecision(
                conversationId, false, false, false, Optional.empty());
    }

    public static ConversationAccessDecision readOnly(ConversationId conversationId) {
        return new ConversationAccessDecision(
                conversationId, true, true, false, Optional.empty());
    }

    public static ConversationAccessDecision readWrite(ConversationId conversationId) {
        return new ConversationAccessDecision(
                conversationId, true, true, true, Optional.empty());
    }

    public static ConversationAccessDecision historical(
            ConversationId conversationId, UtcTimestamp visibleThrough) {
        return new ConversationAccessDecision(
                conversationId,
                true,
                true,
                false,
                Optional.of(Objects.requireNonNull(visibleThrough, "visibleThrough")));
    }

    /** Applies an inclusive cutoff to a committed Message creation time. */
    public boolean canRead(Message message) {
        Message requiredMessage = Objects.requireNonNull(message, "message");
        if (!readable || !requiredMessage.conversationId().equals(conversationId)) {
            return false;
        }
        return historyVisibleThrough
                .map(cutoff -> requiredMessage.audit().createdAt().compareTo(cutoff) <= 0)
                .orElse(true);
    }
}
