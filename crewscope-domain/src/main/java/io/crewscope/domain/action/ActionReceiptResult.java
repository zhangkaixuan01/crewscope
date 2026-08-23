package io.crewscope.domain.action;

/** Immutable conclusion of the sole logical ActionReceipt. */
public enum ActionReceiptResult {
    SUCCEEDED,
    FAILED,
    MANUALLY_SUCCEEDED,
    MANUALLY_FAILED,
    CANCELLED;

    public boolean isSuccessful() {
        return this == SUCCEEDED || this == MANUALLY_SUCCEEDED;
    }

    public boolean isManual() {
        return this == MANUALLY_SUCCEEDED || this == MANUALLY_FAILED;
    }
}
