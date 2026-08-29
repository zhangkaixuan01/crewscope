package io.crewscope.application.identity;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** Current-account profile form with redacted step-up authentication material. */
public final class AccountProfileUpdateCommand {

    private final Optional<String> username;
    private final Optional<String> email;
    private final Optional<String> displayName;
    private final Optional<String> currentPassword;
    private final OptionalLong expectedSecurityVersion;
    private final long expectedVersion;

    public AccountProfileUpdateCommand(
            Optional<String> username,
            Optional<String> email,
            Optional<String> displayName,
            Optional<String> currentPassword,
            OptionalLong expectedSecurityVersion,
            long expectedVersion) {
        this.username = Objects.requireNonNull(username, "username");
        this.email = Objects.requireNonNull(email, "email");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.currentPassword = Objects.requireNonNull(currentPassword, "currentPassword");
        this.expectedSecurityVersion =
                Objects.requireNonNull(expectedSecurityVersion, "expectedSecurityVersion");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
        this.expectedVersion = expectedVersion;
    }

    public Optional<String> username() {
        return username;
    }

    public Optional<String> email() {
        return email;
    }

    public Optional<String> displayName() {
        return displayName;
    }

    public Optional<String> revealCurrentPassword() {
        return currentPassword;
    }

    public OptionalLong expectedSecurityVersion() {
        return expectedSecurityVersion;
    }

    public long expectedVersion() {
        return expectedVersion;
    }

    @Override
    public String toString() {
        return "AccountProfileUpdateCommand[profile=REDACTED, currentPassword=REDACTED,"
                + " expectedSecurityVersion="
                + (expectedSecurityVersion.isPresent() ? "PRESENT" : "ABSENT")
                + ", expectedVersion="
                + expectedVersion
                + "]";
    }
}
