package io.crewscope.domain.retrieval;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Identity of one persisted injection manifest. */
public record InjectionManifestId(UUID value) implements AggregateId {

    public InjectionManifestId {
        value = AggregateId.requireValue(value, "InjectionManifestId");
    }

    public static InjectionManifestId generate() {
        return new InjectionManifestId(AggregateId.generateValue());
    }

    public static InjectionManifestId from(String value) {
        return new InjectionManifestId(AggregateId.parseCanonical(value, "InjectionManifestId"));
    }
}
