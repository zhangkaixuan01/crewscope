package io.crewscope.domain.coding;

import io.crewscope.domain.shared.error.DomainValidationException;

/** Bounded stable failure classification without a raw exception or host detail. */
public record ExecutionWorkspaceFailure(String code) {

    public ExecutionWorkspaceFailure {
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new DomainValidationException(
                    "executionWorkspace.failure.code",
                    "must be a bounded stable upper-snake-case code");
        }
    }
}
