package io.crewscope.domain.organization.governance;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Strongly typed data-export-item aggregate identifier. */
public record DataExportItemId(UUID value) implements AggregateId {

    public DataExportItemId {
        value = AggregateId.requireValue(value, "DataExportItemId");
    }

    public static DataExportItemId generate() {
        return new DataExportItemId(AggregateId.generateValue());
    }

    public static DataExportItemId from(String value) {
        return new DataExportItemId(AggregateId.parseCanonical(value, "DataExportItemId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
