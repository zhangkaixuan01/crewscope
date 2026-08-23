package io.crewscope.infrastructure.github;

import io.crewscope.application.credential.CredentialAccessContext;
import io.crewscope.application.credential.CredentialDescriptor;
import io.crewscope.application.credential.CredentialReference;
import io.crewscope.application.credential.CredentialStatus;
import io.crewscope.application.credential.CredentialStore;
import io.crewscope.application.credential.CredentialSubjectType;
import io.crewscope.application.credential.ResolvedCredential;
import io.crewscope.application.github.GitHubAccessRequest;
import io.crewscope.application.github.GitHubProviderErrorCode;
import io.crewscope.application.github.GitHubProviderException;
import io.crewscope.application.provider.ConnectionGrantRepository;
import io.crewscope.application.provider.ConnectionRepository;
import io.crewscope.domain.provider.Connection;
import io.crewscope.domain.provider.ConnectionGrant;
import io.crewscope.domain.provider.ProviderAccessScope;
import io.crewscope.domain.provider.ProviderOwnerType;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Revalidates generic ConnectionGrant and CredentialStore facts before every GitHub call. */
final class GitHubConnectionGrantAuthorizer {

    static final String CONNECTOR_KEY = "github-source-code";
    static final String CREDENTIAL_TYPE = "GITHUB_TOKEN";

    private final ConnectionRepository connectionRepository;
    private final ConnectionGrantRepository grantRepository;
    private final CredentialStore credentialStore;
    private final TimeProvider timeProvider;
    private final Duration handleTimeToLive;

    GitHubConnectionGrantAuthorizer(
            ConnectionRepository connectionRepository,
            ConnectionGrantRepository grantRepository,
            CredentialStore credentialStore,
            TimeProvider timeProvider,
            Duration handleTimeToLive) {
        this.connectionRepository = Objects.requireNonNull(connectionRepository, "connectionRepository");
        this.grantRepository = Objects.requireNonNull(grantRepository, "grantRepository");
        this.credentialStore = Objects.requireNonNull(credentialStore, "credentialStore");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
        this.handleTimeToLive = Objects.requireNonNull(handleTimeToLive, "handleTimeToLive");
    }

    AuthorizedGitHubAccess authorize(GitHubAccessRequest request, String purpose) {
        GitHubAccessRequest required = Objects.requireNonNull(request, "request");
        UtcTimestamp now = timeProvider.now();
        Connection connection = connectionRepository
                .findById(required.organizationId(), required.connectionId())
                .filter(value -> value.version() == required.expectedConnectionVersion())
                .filter(value -> CONNECTOR_KEY.equals(value.connectorKey()))
                .filter(value -> value.isUsableAt(now))
                .orElseThrow(() -> failure(
                        GitHubProviderErrorCode.CONNECTION_UNAVAILABLE,
                        "GitHub Connection is unavailable"));
        ConnectionGrant grant = grantRepository
                .findById(required.organizationId(), required.connectionGrantId())
                .filter(value -> value.connectionId().equals(connection.id()))
                .filter(value -> value.connectionOwner().equals(connection.owner()))
                .filter(value -> value.grantee().equals(required.grantee()))
                .filter(value -> value.version() == required.expectedGrantVersion())
                .orElseThrow(() -> failure(
                        GitHubProviderErrorCode.GRANT_UNAVAILABLE,
                        "GitHub Connection Grant is unavailable"));
        ProviderAccessScope effective = grant
                .effectiveAccess(required.requestedAccess(), connection, now)
                .orElseThrow(() -> failure(
                        GitHubProviderErrorCode.GRANT_UNAVAILABLE,
                        "GitHub Connection Grant does not authorize this operation"));
        CredentialAccessContext access = new CredentialAccessContext(
                required.organizationId(), required.actor(), Set.of(connection.credentialId()), purpose);
        CredentialReference reference = new CredentialReference(
                required.organizationId(), connection.credentialId());
        CredentialDescriptor descriptor = credentialStore.describe(reference, access)
                .filter(value -> usableCredential(value, connection, now))
                .orElseThrow(() -> failure(
                        GitHubProviderErrorCode.CREDENTIAL_UNAVAILABLE,
                        "GitHub credential is unavailable"));
        validateSubject(descriptor, connection);
        GitHubCredentialHandle handle = new GitHubCredentialHandle(
                connection.id(),
                descriptor.secretVersion(),
                now,
                handleTimeToLive,
                timeProvider,
                () -> resolve(reference, access, descriptor.secretVersion(), connection));
        return new AuthorizedGitHubAccess(connection, grant, effective, handle);
    }

    private ResolvedCredential resolve(
            CredentialReference reference,
            CredentialAccessContext access,
            long secretVersion,
            Connection connection) {
        UtcTimestamp now = timeProvider.now();
        Optional<ResolvedCredential> candidate = credentialStore.resolve(reference, access);
        if (candidate.isEmpty()) {
            throw failure(GitHubProviderErrorCode.CREDENTIAL_UNAVAILABLE,
                    "GitHub credential is unavailable");
        }
        ResolvedCredential resolved = candidate.orElseThrow();
        if (resolved.descriptor().secretVersion() != secretVersion
                || !usableCredential(resolved.descriptor(), connection, now)) {
            resolved.close();
            throw failure(GitHubProviderErrorCode.CREDENTIAL_UNAVAILABLE,
                    "GitHub credential changed before use");
        }
        return resolved;
    }

    private static boolean usableCredential(
            CredentialDescriptor descriptor, Connection connection, UtcTimestamp now) {
        return descriptor.status() == CredentialStatus.ACTIVE
                && descriptor.isUsableAt(now)
                && descriptor.subject().organizationId().equals(connection.organizationId())
                && CONNECTOR_KEY.equals(descriptor.providerKey())
                && CREDENTIAL_TYPE.equals(descriptor.credentialType())
                && descriptor.connectionRef().filter(connection.id().value()::equals).isPresent();
    }

    private static void validateSubject(CredentialDescriptor descriptor, Connection connection) {
        boolean valid = switch (connection.owner().type()) {
            case TEAM -> (descriptor.subject().type() == CredentialSubjectType.TEAM
                            && descriptor.subject().subjectId().equals(connection.owner().ownerId()))
                    || descriptor.subject().type() == CredentialSubjectType.ORGANIZATION;
            case USER -> descriptor.subject().type() == CredentialSubjectType.PRINCIPAL
                    && descriptor.subject().subjectId().equals(connection.owner().ownerId());
            case ORGANIZATION -> false;
        };
        if (!valid || connection.owner().type() == ProviderOwnerType.ORGANIZATION) {
            throw failure(GitHubProviderErrorCode.IDENTITY_MISMATCH,
                    "GitHub credential subject does not match the Connection owner");
        }
    }

    private static GitHubProviderException failure(
            GitHubProviderErrorCode code, String summary) {
        return new GitHubProviderException(code, summary);
    }
}
