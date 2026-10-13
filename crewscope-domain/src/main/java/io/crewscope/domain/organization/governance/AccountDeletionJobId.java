package io.crewscope.domain.organization.governance;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Strongly typed account-deletion-job aggregate identifier. */
public record AccountDeletionJobId(UUID value) implements AggregateId {

    public AccountDeletionJobId {
        value = AggregateId.requireValue(value, "AccountDeletionJobId");
    }

    public static AccountDeletionJobId generate() {
        return new AccountDeletionJobId(AggregateId.generateValue());
    }

    public static AccountDeletionJobId from(String value) {
        return new AccountDeletionJobId(AggregateId.parseCanonical(value, "AccountDeletionJobId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
