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
        requireSafeText(markdown);
    }

    private static void requireSafeText(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw unsafeContent();
                }
                index++;
                continue;
            }
            if (Character.isLowSurrogate(current)
                    || (Character.isISOControl(current) && !allowedWhitespace(current))
                    || isBidirectionalControl(current)) {
                throw unsafeContent();
            }
        }
    }

    private static boolean allowedWhitespace(char value) {
        return value == '\t' || value == '\n' || value == '\r';
    }

    private static boolean isBidirectionalControl(char value) {
        // Reject every code point in Unicode's Bidi_Control property to prevent visual spoofing.
        return value == '\u061C'
                || value == '\u200E'
                || value == '\u200F'
                || (value >= '\u202A' && value <= '\u202E')
                || (value >= '\u2066' && value <= '\u2069');
    }

    private static DomainValidationException unsafeContent() {
        return new DomainValidationException(
                "message.content", "contains unsupported control characters");
    }
}
