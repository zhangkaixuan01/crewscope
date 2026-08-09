package io.crewscope.application.execution;

import java.util.Objects;

/** Safe fail-closed result of rebuilding current execution authorization facts. */
public final class PlatformExecutionContextResolutionException extends RuntimeException {

    private final String safeCode;

    public PlatformExecutionContextResolutionException(String safeCode) {
        super("The current execution authorization facts could not be resolved.");
        this.safeCode = requireCode(safeCode);
    }

    public String safeCode() {
        return safeCode;
    }

    private static String requireCode(String value) {
        String required = Objects.requireNonNull(value, "safeCode").strip();
        if (required.isEmpty() || !required.matches("[A-Z][A-Z0-9_]{2,99}")) {
            throw new IllegalArgumentException("safeCode must be a stable uppercase code");
        }
        return required;
    }
}
