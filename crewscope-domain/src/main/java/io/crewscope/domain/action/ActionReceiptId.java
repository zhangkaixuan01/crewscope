package io.crewscope.domain.action;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Stable identity of the sole logical external-side-effect result for an action. */
public record ActionReceiptId(UUID value) implements AggregateId {

    public ActionReceiptId {
        value = AggregateId.requireValue(value, "ActionReceiptId");
    }

    public static ActionReceiptId generate() {
        return new ActionReceiptId(AggregateId.generateValue());
    }
}
