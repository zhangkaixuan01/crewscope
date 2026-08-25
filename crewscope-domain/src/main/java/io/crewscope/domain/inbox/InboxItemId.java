package io.crewscope.domain.inbox;

import io.crewscope.domain.shared.id.AggregateId;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** Stable Inbox identity shared by all projection generations and member dispositions. */
public record InboxItemId(UUID value) implements AggregateId {

    private static final String NAMESPACE = "crewscope:inbox-item:v1:";

    public InboxItemId {
        value = AggregateId.requireValue(value, "InboxItemId");
    }

    public static InboxItemId fromSource(InboxSourceKey sourceKey) {
        InboxSourceKey required = Objects.requireNonNull(sourceKey, "sourceKey");
        return new InboxItemId(UUID.nameUUIDFromBytes(
                (NAMESPACE + required.canonicalIdentity()).getBytes(StandardCharsets.UTF_8)));
    }

    public InboxItemId requireSource(InboxSourceKey sourceKey) {
        if (!equals(fromSource(Objects.requireNonNull(sourceKey, "sourceKey")))) {
            throw new IllegalArgumentException(
                    "InboxItemId must be derived from the canonical Inbox source key");
        }
        return this;
    }

    public static InboxItemId from(String value) {
        return new InboxItemId(AggregateId.parseCanonical(value, "InboxItemId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
