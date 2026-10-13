package io.crewscope.domain.organization.governance;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Strongly typed data-export-job aggregate identifier. */
public record DataExportJobId(UUID value) implements AggregateId {

    public DataExportJobId {
        value = AggregateId.requireValue(value, "DataExportJobId");
    }

    public static DataExportJobId generate() {
        return new DataExportJobId(AggregateId.generateValue());
    }

    public static DataExportJobId from(String value) {
        return new DataExportJobId(AggregateId.parseCanonical(value, "DataExportJobId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
