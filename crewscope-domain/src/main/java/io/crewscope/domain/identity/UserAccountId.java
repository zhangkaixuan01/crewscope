package io.crewscope.domain.identity;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Stable deployment-level identifier of one login account. */
public record UserAccountId(UUID value) implements AggregateId {

    public UserAccountId {
        value = AggregateId.requireValue(value, "UserAccountId");
    }

    public static UserAccountId generate() {
        return new UserAccountId(AggregateId.generateValue());
    }

    public static UserAccountId from(String value) {
        return new UserAccountId(AggregateId.parseCanonical(value, "UserAccountId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
