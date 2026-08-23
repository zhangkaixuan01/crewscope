package io.crewscope.application.action;

import io.crewscope.domain.action.ActionReceipt;
import java.util.Objects;

/** Atomic unique-insert result for the sole Organization + PlannedAction Receipt. */
public record ActionReceiptInsertResult(boolean inserted, ActionReceipt receipt) {

    public ActionReceiptInsertResult {
        receipt = Objects.requireNonNull(receipt, "receipt");
    }
}
