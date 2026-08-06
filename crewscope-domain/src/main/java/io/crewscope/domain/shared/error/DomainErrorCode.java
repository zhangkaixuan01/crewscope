package io.crewscope.domain.shared.error;

/** Stable machine-readable domain error codes. */
public enum DomainErrorCode {
    INVALID_VALUE("invalid_value", DomainErrorCategory.VALIDATION),
    RULE_VIOLATION("rule_violation", DomainErrorCategory.VALIDATION),
    INVALID_STATE_TRANSITION("invalid_state_transition", DomainErrorCategory.CONFLICT),
    AGGREGATE_NOT_FOUND("aggregate_not_found", DomainErrorCategory.NOT_FOUND),
    OPTIMISTIC_LOCK_CONFLICT("optimistic_lock_conflict", DomainErrorCategory.CONFLICT),
    IDEMPOTENCY_CONFLICT("idempotency_conflict", DomainErrorCategory.CONFLICT);

    private final String value;
    private final DomainErrorCategory category;

    DomainErrorCode(String value, DomainErrorCategory category) {
        this.value = value;
        this.category = category;
    }

    public String value() {
        return value;
    }

    public DomainErrorCategory category() {
        return category;
    }
}
