package io.crewscope.server.security.session;

import java.io.Serial;
import java.io.Serializable;
import java.security.Principal;
import java.util.Objects;
import java.util.UUID;

/**
 * Minimal authenticated Account identity permitted inside a browser Session.
 *
 * <p>{@code identityProvider} names the credential that established the Session (M12-I01): {@code
 * local} for identifier+password and {@code oidc/<registrationId>} for federated logins. Sessions
 * written before the field existed deserialize it as {@code null}; the canonical constructor
 * normalizes every absent value to {@code local}, so upgraded deployments keep resolving legacy
 * Sessions without a migration.
 */
public record BrowserSessionPrincipal(UUID accountId, long securityVersion, String identityProvider)
        implements Principal, Serializable {

    @Serial private static final long serialVersionUID = 1L;

    public BrowserSessionPrincipal {
        accountId = Objects.requireNonNull(accountId, "accountId");
        if (securityVersion < 1) {
            throw new IllegalArgumentException("securityVersion must be positive");
        }
        identityProvider =
                identityProvider == null || identityProvider.isBlank()
                        ? "local"
                        : identityProvider;
    }

    public BrowserSessionPrincipal(UUID accountId, long securityVersion) {
        this(accountId, securityVersion, "local");
    }

    @Override
    public String getName() {
        return accountId.toString();
    }
}
