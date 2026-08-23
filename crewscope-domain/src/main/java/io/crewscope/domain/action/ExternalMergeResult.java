package io.crewscope.domain.action;

import java.util.Objects;

/** Result and decision of the single ExternalResult merge function. */
public record ExternalMergeResult(ExternalResult result, ExternalMergeOutcome outcome) {

    public ExternalMergeResult {
        result = Objects.requireNonNull(result, "result");
        outcome = Objects.requireNonNull(outcome, "outcome");
    }

    public boolean changed() {
        return outcome == ExternalMergeOutcome.APPLIED;
    }
}
