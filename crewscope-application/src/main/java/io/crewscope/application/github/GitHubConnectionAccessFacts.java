package io.crewscope.application.github;

import io.crewscope.domain.provider.Connection;
import io.crewscope.domain.provider.ConnectionGrant;
import java.util.Objects;

/** Current Connection, Grant and policy facts resolved for one managed access. */
record GitHubConnectionAccessFacts(
        Connection connection,
        ConnectionGrant grant,
        GitHubAuthenticationType authenticationType,
        GitHubRepositoryPolicy policy,
        GitHubAccessRequest request) {

    GitHubConnectionAccessFacts {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(grant, "grant");
        Objects.requireNonNull(authenticationType, "authenticationType");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(request, "request");
    }
}
