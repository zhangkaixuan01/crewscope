package io.crewscope.application.identity;

import java.util.Objects;

/** Password rotation input whose two password values never enter diagnostics. */
public final class AccountPasswordChangeCommand {

    private final String currentPassword;
    private final String newPassword;
    private final long expectedSecurityVersion;
    private final long expectedVersion;

    public AccountPasswordChangeCommand(
            String currentPassword,
            String newPassword,
            long expectedSecurityVersion,
            long expectedVersion) {
        this.currentPassword = Objects.requireNonNull(currentPassword, "currentPassword");
        this.newPassword = Objects.requireNonNull(newPassword, "newPassword");
        if (expectedSecurityVersion < 1 || expectedVersion < 0) {
            throw new IllegalArgumentException("Expected versions are outside their supported range");
        }
        this.expectedSecurityVersion = expectedSecurityVersion;
        this.expectedVersion = expectedVersion;
    }

    public String revealCurrentPassword() {
        return currentPassword;
    }

    public String revealNewPassword() {
        return newPassword;
    }

    public long expectedSecurityVersion() {
        return expectedSecurityVersion;
    }

    public long expectedVersion() {
        return expectedVersion;
    }

    @Override
    public String toString() {
        return "AccountPasswordChangeCommand[currentPassword=REDACTED, newPassword=REDACTED,"
                + " expectedSecurityVersion="
                + expectedSecurityVersion
                + ", expectedVersion="
                + expectedVersion
                + "]";
    }
}
