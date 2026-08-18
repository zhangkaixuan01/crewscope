package io.crewscope.domain.coding;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Stable identity of one WorkProject-scoped source repository binding. */
public record RepositoryBindingId(UUID value) implements AggregateId {

    public RepositoryBindingId {
        value = AggregateId.requireValue(value, "RepositoryBindingId");
    }

    public static RepositoryBindingId generate() {
        return new RepositoryBindingId(AggregateId.generateValue());
    }

    public static RepositoryBindingId from(String value) {
        return new RepositoryBindingId(
                AggregateId.parseCanonical(value, "RepositoryBindingId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
