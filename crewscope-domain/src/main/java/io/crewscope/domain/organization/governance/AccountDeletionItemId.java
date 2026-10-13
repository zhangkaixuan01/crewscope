package io.crewscope.domain.organization.governance;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Strongly typed account-deletion-item aggregate identifier. */
public record AccountDeletionItemId(UUID value) implements AggregateId {

    public AccountDeletionItemId {
        value = AggregateId.requireValue(value, "AccountDeletionItemId");
    }

    public static AccountDeletionItemId generate() {
        return new AccountDeletionItemId(AggregateId.generateValue());
    }

    public static AccountDeletionItemId from(String value) {
        return new AccountDeletionItemId(AggregateId.parseCanonical(value, "AccountDeletionItemId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
