package io.crewscope.server.security.session;

import java.io.Serial;
import java.io.Serializable;
import java.security.Principal;
import java.util.Objects;
import java.util.UUID;

/** Minimal authenticated Account identity permitted inside a browser Session. */
public record BrowserSessionPrincipal(UUID accountId, long securityVersion)
        implements Principal, Serializable {

    @Serial private static final long serialVersionUID = 1L;

    public BrowserSessionPrincipal {
        accountId = Objects.requireNonNull(accountId, "accountId");
        if (securityVersion < 1) {
            throw new IllegalArgumentException("securityVersion must be positive");
        }
    }

    @Override
    public String getName() {
        return accountId.toString();
    }
}
