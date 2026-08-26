package io.crewscope.integration.provider.collaboration;

import io.crewscope.domain.collaboration.LarkOpenId;
import java.util.Objects;
import java.util.UUID;

/** Fixed text-message operation; template selection and rendering remain owned by M6-I06. */
record LarkTextMessageRequest(
        LarkOpenId recipient,
        String text,
        LarkNotificationUuid idempotencyKey) {

    public LarkTextMessageRequest {
        recipient = Objects.requireNonNull(recipient, "recipient");
        idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        if (text == null || text.isBlank() || text.length() > 4_000
                || text.codePoints().anyMatch(LarkTextMessageRequest::unsafeCodePoint)) {
            throw new IllegalArgumentException("Lark fixed text is outside its safe bounds");
        }
    }

    /** Compatibility constructor for callers that hold CrewScope's typed UUID coordinate. */
    LarkTextMessageRequest(LarkOpenId recipient, String text, UUID idempotencyKey) {
        this(recipient, text, LarkNotificationUuid.from(idempotencyKey));
    }

    @Override
    public String toString() {
        return "LarkTextMessageRequest[recipient=REDACTED, text=REDACTED, idempotencyKey=REDACTED]";
    }

    private static boolean unsafeCodePoint(int value) {
        return (Character.isISOControl(value) && value != '\n' && value != '\r' && value != '\t')
                || Character.getType(value) == Character.FORMAT;
    }
}
