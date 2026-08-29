package io.crewscope.application.identity;

import java.util.Objects;

/** Untrusted login values whose password and identifier are always redacted from diagnostics. */
public final class LocalAccountLoginCommand {

    private final String identifier;
    private final String password;

    public LocalAccountLoginCommand(String identifier, String password) {
        this.identifier = Objects.requireNonNull(identifier, "identifier");
        this.password = Objects.requireNonNull(password, "password");
    }

    public String identifier() {
        return identifier;
    }

    /** Exposes the password only to the trusted password-verification boundary. */
    public String revealPassword() {
        return password;
    }

    @Override
    public String toString() {
        return "LocalAccountLoginCommand[identifier=REDACTED, password=REDACTED]";
    }
}
