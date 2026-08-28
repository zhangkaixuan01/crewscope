package io.crewscope.domain.identity;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Stable identifier of one provider identity linked to a user account. */
public record LoginIdentityId(UUID value) implements AggregateId {

    public LoginIdentityId {
        value = AggregateId.requireValue(value, "LoginIdentityId");
    }

    public static LoginIdentityId generate() {
        return new LoginIdentityId(AggregateId.generateValue());
    }

    public static LoginIdentityId from(String value) {
        return new LoginIdentityId(AggregateId.parseCanonical(value, "LoginIdentityId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
