package io.crewscope.domain.task;

import io.crewscope.domain.shared.error.DomainValidationException;

/** Finite execution budget pinned by a PolicySnapshot. */
public record PolicyBudget(long maxTokens, int maxModelCalls, int maxToolCalls, long maxDurationSeconds) {
    public PolicyBudget {
        if (maxTokens < 1 || maxModelCalls < 1 || maxToolCalls < 1 || maxDurationSeconds < 1) {
            throw new DomainValidationException(
                    "policySnapshot.budget", "all limits must be positive");
        }
    }
}
