package io.crewscope.infrastructure.github;

import io.crewscope.domain.provider.Connection;
import io.crewscope.domain.provider.ConnectionGrant;
import io.crewscope.domain.provider.ProviderAccessScope;
import java.util.Objects;

/** Current exact Connection, Grant and intersection frozen for one provider call. */
record AuthorizedGitHubAccess(
        Connection connection,
        ConnectionGrant grant,
        ProviderAccessScope effectiveAccess,
        GitHubCredentialHandle credentialHandle) implements AutoCloseable {

    AuthorizedGitHubAccess {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(grant, "grant");
        Objects.requireNonNull(effectiveAccess, "effectiveAccess");
        Objects.requireNonNull(credentialHandle, "credentialHandle");
    }

    @Override
    public void close() {
        credentialHandle.close();
    }
}
