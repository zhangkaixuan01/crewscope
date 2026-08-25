package io.crewscope.domain.notification;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Stable identifier of the team policy authorizing collaboration notifications. */
public record TeamNotificationPolicyId(UUID value) implements AggregateId {
    public TeamNotificationPolicyId {
        value = AggregateId.requireValue(value, "TeamNotificationPolicyId");
    }

    public static TeamNotificationPolicyId generate() {
        return new TeamNotificationPolicyId(AggregateId.generateValue());
    }
}
