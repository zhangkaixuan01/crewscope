package io.crewscope.application.execution;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** Sanitized failure fact; exception objects and sensitive provider messages stay in the adapter. */
public record ExecutionFailure(
        ExecutionFailureCategory category,
        boolean retryable,
        String safeMessage,
        Optional<String> runtimeCode) {

    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_.-]{0,127}");

    public ExecutionFailure {
        category = Objects.requireNonNull(category, "category");
        safeMessage = Objects.requireNonNull(safeMessage, "safeMessage").strip();
        runtimeCode = Objects.requireNonNull(runtimeCode, "runtimeCode")
                .map(String::strip);
        if (safeMessage.isEmpty() || safeMessage.length() > 500 || containsControl(safeMessage)) {
            throw new IllegalArgumentException(
                    "safeMessage must contain 1 to 500 printable characters");
        }
        if (runtimeCode.filter(value -> !CODE.matcher(value).matches()).isPresent()) {
            throw new IllegalArgumentException("runtimeCode must use a stable uppercase code");
        }
    }

    private static boolean containsControl(String value) {
        return value.chars().anyMatch(character -> Character.isISOControl(character));
    }
}
