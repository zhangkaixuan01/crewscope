package io.crewscope.agentscope;

import java.util.Objects;

/** Safe fail-closed error raised before a model or tool can consume an invalid platform context. */
public final class PlatformExecutionSecurityException extends RuntimeException {

    private final String safeCode;

    public PlatformExecutionSecurityException(String safeCode) {
        super("The Agent execution context is not authorized.");
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
