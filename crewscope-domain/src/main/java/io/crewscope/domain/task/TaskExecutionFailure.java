package io.crewscope.domain.task;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.Objects;

/** Safe terminal failure fact; raw exception text and provider payloads never enter the aggregate. */
public record TaskExecutionFailure(TaskExecutionFailureClass failureClass, String code) {

    private static final String SAFE_CODE_PATTERN = "[A-Z][A-Z0-9_]{0,63}";

    public TaskExecutionFailure {
        failureClass = Objects.requireNonNull(failureClass, "failureClass");
        code = requireSafeCode(code);
    }

    public boolean isRetryable() {
        return failureClass.isRetryable();
    }

    private static String requireSafeCode(String value) {
        if (value == null || !value.matches(SAFE_CODE_PATTERN)) {
            throw new DomainValidationException(
                    "taskExecution.failure.code",
                    "must be an uppercase stable code of at most 64 characters");
        }
        return value;
    }
}
