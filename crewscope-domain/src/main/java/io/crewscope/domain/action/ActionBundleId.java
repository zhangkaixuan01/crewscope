package io.crewscope.domain.action;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Stable identity of one immutable external-action review unit. */
public record ActionBundleId(UUID value) implements AggregateId {

    public ActionBundleId {
        value = AggregateId.requireValue(value, "ActionBundleId");
    }

    public static ActionBundleId generate() {
        return new ActionBundleId(AggregateId.generateValue());
    }
}
