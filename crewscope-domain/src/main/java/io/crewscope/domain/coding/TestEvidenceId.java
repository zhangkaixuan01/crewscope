package io.crewscope.domain.coding;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Stable identity of one immutable test and acceptance evidence set. */
public record TestEvidenceId(UUID value) implements AggregateId {

    public TestEvidenceId {
        value = AggregateId.requireValue(value, "TestEvidenceId");
    }

    public static TestEvidenceId generate() {
        return new TestEvidenceId(AggregateId.generateValue());
    }

    public static TestEvidenceId from(String value) {
        return new TestEvidenceId(AggregateId.parseCanonical(value, "TestEvidenceId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
