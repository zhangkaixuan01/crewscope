package io.crewscope.application.identity;

import java.util.Objects;

/** Step-up authenticated all-session revocation input with redacted password diagnostics. */
public final class AccountSessionRevocationCommand {

    private final String currentPassword;
    private final long expectedSecurityVersion;
    private final long expectedVersion;

    public AccountSessionRevocationCommand(
            String currentPassword, long expectedSecurityVersion, long expectedVersion) {
        this.currentPassword = Objects.requireNonNull(currentPassword, "currentPassword");
        if (expectedSecurityVersion < 1 || expectedVersion < 0) {
            throw new IllegalArgumentException("Expected versions are outside their supported range");
        }
        this.expectedSecurityVersion = expectedSecurityVersion;
        this.expectedVersion = expectedVersion;
    }

    public String revealCurrentPassword() {
        return currentPassword;
    }

    public long expectedSecurityVersion() {
        return expectedSecurityVersion;
    }

    public long expectedVersion() {
        return expectedVersion;
    }

    @Override
    public String toString() {
        return "AccountSessionRevocationCommand[currentPassword=REDACTED,"
                + " expectedSecurityVersion="
                + expectedSecurityVersion
                + ", expectedVersion="
                + expectedVersion
                + "]";
    }
}
