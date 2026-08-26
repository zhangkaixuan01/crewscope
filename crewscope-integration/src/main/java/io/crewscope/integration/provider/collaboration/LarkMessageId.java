package io.crewscope.integration.provider.collaboration;

import java.util.regex.Pattern;

/** Exact bounded message_id accepted by the fixed Lark query operation. */
public record LarkMessageId(String value) {

    private static final Pattern FORMAT = Pattern.compile("om_[A-Za-z0-9_-]{1,120}");

    public LarkMessageId {
        if (value == null || !FORMAT.matcher(value.strip()).matches()) {
            throw new IllegalArgumentException("Lark message_id has an invalid shape");
        }
        value = value.strip();
    }

    @Override
    public String toString() {
        return "LarkMessageId[REDACTED]";
    }
}
