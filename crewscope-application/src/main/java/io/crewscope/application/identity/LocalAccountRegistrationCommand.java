package io.crewscope.application.identity;

import io.crewscope.application.team.InvitationToken;
import java.util.Objects;
import java.util.Optional;

/** Untrusted local-registration values with an explicitly redacted password and invitation token. */
public final class LocalAccountRegistrationCommand {

    private final String username;
    private final String email;
    private final String displayName;
    private final String password;
    private final Optional<InvitationToken> invitationToken;

    public LocalAccountRegistrationCommand(
            String username,
            String email,
            String displayName,
            String password,
            Optional<InvitationToken> invitationToken) {
        this.username = Objects.requireNonNull(username, "username");
        this.email = Objects.requireNonNull(email, "email");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.password = Objects.requireNonNull(password, "password");
        this.invitationToken = Objects.requireNonNull(invitationToken, "invitationToken");
    }

    public String username() {
        return username;
    }

    public String email() {
        return email;
    }

    public String displayName() {
        return displayName;
    }

    /** Exposes the password only to the password boundary and never through diagnostics. */
    public String revealPassword() {
        return password;
    }

    public Optional<InvitationToken> invitationToken() {
        return invitationToken;
    }

    @Override
    public String toString() {
        return "LocalAccountRegistrationCommand[identity=REDACTED, password=REDACTED,"
                + " invitationToken="
                + (invitationToken.isPresent() ? "PRESENT" : "ABSENT")
                + "]";
    }
}
