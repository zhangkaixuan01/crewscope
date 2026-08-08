package io.crewscope.domain.conversation;

import io.crewscope.domain.shared.error.DomainValidationException;

/** Normalized Markdown content persisted as one immutable Message payload. */
public record MessageContent(String markdown) {

    public static final int MAX_LENGTH = 50_000;

    public MessageContent {
        if (markdown == null || markdown.isBlank()) {
            throw new DomainValidationException("message.content", "must not be blank");
        }
        markdown = markdown.strip();
        if (markdown.length() > MAX_LENGTH) {
            throw new DomainValidationException(
                    "message.content", "must contain at most " + MAX_LENGTH + " characters");
        }
    }
}
