package io.crewscope.application.action;

/** Low-cardinality aggregate of one bounded reconciliation poll. */
public record ActionReconciliationBatchResult(
        int claimed,
        int succeeded,
        int inconclusive,
        int manualReview,
        int failed) {

    public ActionReconciliationBatchResult {
        if (claimed < 0 || succeeded < 0 || inconclusive < 0 || manualReview < 0 || failed < 0
                || succeeded + inconclusive + manualReview + failed != claimed) {
            throw new IllegalArgumentException("Action reconciliation counters are invalid");
        }
    }

    public static ActionReconciliationBatchResult empty() {
        return new ActionReconciliationBatchResult(0, 0, 0, 0, 0);
    }
}
