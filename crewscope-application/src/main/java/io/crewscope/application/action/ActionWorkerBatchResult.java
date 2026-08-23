package io.crewscope.application.action;

/** Low-cardinality result of one bounded Action Worker poll. */
public record ActionWorkerBatchResult(
        int claimed,
        int succeeded,
        int failed,
        int unknown,
        int rescheduled) {

    public ActionWorkerBatchResult {
        if (claimed < 0 || succeeded < 0 || failed < 0 || unknown < 0 || rescheduled < 0
                || succeeded + failed + unknown + rescheduled != claimed) {
            throw new IllegalArgumentException("Action Worker batch counters are invalid");
        }
    }

    public static ActionWorkerBatchResult empty() {
        return new ActionWorkerBatchResult(0, 0, 0, 0, 0);
    }
}
