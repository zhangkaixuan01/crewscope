package io.crewscope.domain.action;

/** Normalized Provider state used by monotonic ExternalResult merging. */
public enum ExternalObjectStatus {
    PRESENT,
    MISSING,
    OPEN,
    CLOSED,
    MERGED;

    public boolean supports(ExternalObjectType type) {
        return switch (java.util.Objects.requireNonNull(type, "type")) {
            case BRANCH -> this == PRESENT || this == MISSING;
            case PULL_REQUEST -> this == OPEN || this == CLOSED || this == MERGED;
        };
    }

    public boolean canTransitionTo(ExternalObjectStatus target) {
        ExternalObjectStatus required = java.util.Objects.requireNonNull(target, "target");
        if (this == MERGED) {
            return required == MERGED;
        }
        return true;
    }
}
