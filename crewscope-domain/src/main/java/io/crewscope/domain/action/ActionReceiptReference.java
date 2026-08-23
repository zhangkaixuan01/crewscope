package io.crewscope.domain.action;

import java.util.Objects;

/** Terminal immutable Receipt coordinates pinned on an ActionDispatch. */
public record ActionReceiptReference(
        ActionReceiptId id,
        PlannedActionId actionId,
        ActionDigest actionDigest,
        ActionReceiptResult result) {

    public ActionReceiptReference {
        id = Objects.requireNonNull(id, "id");
        actionId = Objects.requireNonNull(actionId, "actionId");
        actionDigest = Objects.requireNonNull(actionDigest, "actionDigest");
        result = Objects.requireNonNull(result, "result");
    }
}
