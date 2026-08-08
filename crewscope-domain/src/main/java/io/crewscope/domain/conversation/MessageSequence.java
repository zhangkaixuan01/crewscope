package io.crewscope.domain.conversation;

import io.crewscope.domain.shared.error.DomainValidationException;

/** Positive, gap-free ordering position allocated inside one Conversation. */
public record MessageSequence(long value) implements Comparable<MessageSequence> {

    public MessageSequence {
        if (value <= 0) {
            throw new DomainValidationException("message.sequence", "must be positive");
        }
    }

    public static MessageSequence first() {
        return new MessageSequence(1);
    }

    public MessageSequence next() {
        if (value == Long.MAX_VALUE) {
            throw new DomainValidationException(
                    "message.sequence", "has reached the supported maximum");
        }
        return new MessageSequence(value + 1);
    }

    @Override
    public int compareTo(MessageSequence other) {
        return Long.compare(value, other.value);
    }
}
