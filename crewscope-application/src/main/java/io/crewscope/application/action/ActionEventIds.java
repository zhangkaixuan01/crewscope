package io.crewscope.application.action;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** Stable identity shared by Action event publication and command receipts. */
final class ActionEventIds {

    private ActionEventIds() {}

    static UUID stable(String type, UUID aggregateId, long aggregateVersion) {
        if (aggregateVersion < 0) {
            throw new IllegalArgumentException("aggregateVersion must not be negative");
        }
        String source = "crewscope:action:" + Objects.requireNonNull(type, "type") + ':'
                + Objects.requireNonNull(aggregateId, "aggregateId") + ':' + aggregateVersion;
        return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8));
    }
}
