package io.crewscope.integration.provider.collaboration;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Fixed 32-character Provider idempotency coordinate with no embedded PII. */
public record LarkNotificationUuid(String value) {

    public LarkNotificationUuid {
        if (value == null || !value.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException(
                    "Lark notification UUID must contain 32 lowercase hexadecimal characters");
        }
    }

    public static LarkNotificationUuid from(UUID value) {
        return new LarkNotificationUuid(Objects.requireNonNull(value, "value")
                .toString().replace("-", "").toLowerCase(Locale.ROOT));
    }

    @Override
    public String toString() {
        return value;
    }
}
