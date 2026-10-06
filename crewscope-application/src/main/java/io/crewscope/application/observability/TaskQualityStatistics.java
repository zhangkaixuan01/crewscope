package io.crewscope.application.observability;

/**
 * Team quality counters for one reporting month window (M10-F03). Execution attempts are
 * counted by attempt (denominator of the success rate); the review first-pass figures
 * count each task's earliest non-invalidated review request recorded inside the window —
 * a task whose reviews span months counts in the month of its first in-window request.
 */
public record TaskQualityStatistics(
        long executionAttempts,
        long completedAttempts,
        long failedAttempts,
        long cancelledAttempts,
        long enteredReview,
        long firstPassApproved) {

    public TaskQualityStatistics {
        if (executionAttempts < 0 || completedAttempts < 0 || failedAttempts < 0
                || cancelledAttempts < 0 || enteredReview < 0 || firstPassApproved < 0) {
            throw new IllegalArgumentException("quality counters must be non-negative");
        }
        if (completedAttempts + failedAttempts + cancelledAttempts > executionAttempts) {
            throw new IllegalArgumentException("terminal counters exceed execution attempts");
        }
        if (firstPassApproved > enteredReview) {
            throw new IllegalArgumentException("first-pass approvals exceed entered reviews");
        }
    }

    /** The three-way split leaves non-terminal attempts out of the success denominator view. */
    public long nonTerminalAttempts() {
        return executionAttempts - completedAttempts - failedAttempts - cancelledAttempts;
    }
}
