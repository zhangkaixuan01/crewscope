package io.crewscope.domain.model;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Stable identity shared by every revision of one model catalog entry. */
public record ModelCatalogEntryId(UUID value) implements AggregateId {

    public ModelCatalogEntryId {
        value = AggregateId.requireValue(value, "ModelCatalogEntryId");
    }

    public static ModelCatalogEntryId generate() {
        return new ModelCatalogEntryId(AggregateId.generateValue());
    }

    public static ModelCatalogEntryId from(String value) {
        return new ModelCatalogEntryId(
                AggregateId.parseCanonical(value, "ModelCatalogEntryId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
