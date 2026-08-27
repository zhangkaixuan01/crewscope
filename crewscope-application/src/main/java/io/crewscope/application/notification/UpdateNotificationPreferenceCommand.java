package io.crewscope.application.notification;

import io.crewscope.domain.inbox.InboxItemType;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Strong-versioned fixed preference update; arbitrary template or message content is absent. */
public record UpdateNotificationPreferenceCommand(
        boolean enabled,
        Set<InboxItemType> enabledItemTypes,
        Optional<UtcTimestamp> mutedUntil,
        long expectedVersion) {

    public UpdateNotificationPreferenceCommand {
        enabledItemTypes = Set.copyOf(
                Objects.requireNonNull(enabledItemTypes, "enabledItemTypes"));
        mutedUntil = Objects.requireNonNull(mutedUntil, "mutedUntil");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
    }
}
