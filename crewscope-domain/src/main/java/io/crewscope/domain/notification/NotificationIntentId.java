package io.crewscope.domain.notification;

import io.crewscope.domain.inbox.InboxItemId;
import io.crewscope.domain.shared.id.AggregateId;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** Stable notification intent derived from one generation-independent Inbox item. */
public record NotificationIntentId(UUID value) implements AggregateId {

    public NotificationIntentId {
        value = AggregateId.requireValue(value, "NotificationIntentId");
    }

    public static NotificationIntentId fromInboxItem(InboxItemId itemId) {
        String source = "notification-intent-v1:" + Objects.requireNonNull(itemId, "itemId");
        return new NotificationIntentId(UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8)));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
