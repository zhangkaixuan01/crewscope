package io.crewscope.application.identity;

import io.crewscope.domain.shared.id.OrganizationId;
import java.util.Objects;

/** Validated deployment coordinates whose string form never exposes the external password. */
public final class BootstrapOperatorProvisioning {

    public static final String LEGACY_PROVIDER = "bootstrap";
    public static final String LEGACY_SUBJECT = "crewscope-monitor";

    private final OrganizationId organizationId;
    private final String username;
    private final String email;
    private final String displayName;
    private final String password;

    public BootstrapOperatorProvisioning(
            OrganizationId organizationId,
            String username,
            String email,
            String displayName,
            String password) {
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId");
        this.username = requireText(username, "username");
        this.email = requireText(email, "email");
        this.displayName = requireText(displayName, "displayName");
        this.password = requireText(password, "password");
    }

    public OrganizationId organizationId() {
        return organizationId;
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

    /** Exposes the Secret only to the trusted bootstrap password boundary. */
    public String revealPassword() {
        return password;
    }

    @Override
    public String toString() {
        return "BootstrapOperatorProvisioning[organizationId="
                + organizationId
                + ", username="
                + username
                + ", email=[REDACTED], displayName="
                + displayName
                + ", password=[REDACTED]]";
    }

    private static String requireText(String value, String name) {
        String required = Objects.requireNonNull(value, name);
        if (required.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return required.strip();
    }
}
