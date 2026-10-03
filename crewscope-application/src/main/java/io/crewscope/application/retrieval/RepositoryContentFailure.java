package io.crewscope.application.retrieval;

/**
 * Unrecoverable repository-content read (M10-I01b): carries the stable operator-facing
 * failure code (for example {@code REPOSITORY_TOO_LARGE}) that lands unchanged on the
 * job's {@code failure_code}; never carries provider or transport detail.
 */
public final class RepositoryContentFailure extends RuntimeException {

    private final String failureCode;

    public RepositoryContentFailure(String failureCode, String message) {
        super(message);
        this.failureCode = requireCode(failureCode);
    }

    public String failureCode() {
        return failureCode;
    }

    private static String requireCode(String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty() || normalized.length() > 80) {
            throw new IllegalArgumentException(
                    "failureCode must contain 1 to 80 characters");
        }
        return normalized;
    }
}
