package io.crewscope.integration.provider.collaboration;

import io.crewscope.application.credential.CredentialAccessContext;
import io.crewscope.application.credential.CredentialDescriptor;
import io.crewscope.application.credential.CredentialReference;
import io.crewscope.application.credential.CredentialStatus;
import io.crewscope.application.credential.CredentialStore;
import io.crewscope.application.credential.CredentialSubjectType;
import io.crewscope.application.credential.ResolvedCredential;
import io.crewscope.application.provider.ConnectionGrantRepository;
import io.crewscope.application.provider.ConnectionRepository;
import io.crewscope.domain.collaboration.LarkConnectionAuthorization;
import io.crewscope.domain.collaboration.LarkCollaborationCapabilities;
import io.crewscope.domain.provider.Connection;
import io.crewscope.domain.provider.ConnectionGrant;
import io.crewscope.domain.provider.ProviderCapabilities;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Revalidates Connection, Grant and CredentialStore facts before every Lark API operation. */
public final class LarkCredentialAccessManager {

    public static final String CONNECTOR_KEY = LarkCollaborationCapabilities.CONNECTOR_KEY;
    public static final String CREDENTIAL_TYPE = "LARK_APP_CREDENTIAL";

    private final ConnectionRepository connections;
    private final ConnectionGrantRepository grants;
    private final CredentialStore credentials;
    private final TimeProvider timeProvider;
    private final Duration handleTimeToLive;

    public LarkCredentialAccessManager(
            ConnectionRepository connections,
            ConnectionGrantRepository grants,
            CredentialStore credentials,
            TimeProvider timeProvider,
            Duration handleTimeToLive) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.grants = Objects.requireNonNull(grants, "grants");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
        this.handleTimeToLive = requireTtl(handleTimeToLive);
    }

    AuthorizedLarkAccess authorize(
            LarkApiCallContext context,
            String purpose,
            Optional<ProviderCapabilities> requiredCapabilities) {
        return authorize(context, purpose, requiredCapabilities, handleTimeToLive);
    }

    AuthorizedLarkAccess authorize(
            LarkApiCallContext context,
            String purpose,
            Optional<ProviderCapabilities> requiredCapabilities,
            Duration requestedTimeToLive) {
        LarkApiCallContext required = Objects.requireNonNull(context, "context");
        Duration requestedTtl = requireTtl(requestedTimeToLive);
        Duration effectiveTtl = requestedTtl.compareTo(handleTimeToLive) > 0
                ? handleTimeToLive
                : requestedTtl;
        LarkConnectionAuthorization authorization = required.authorization();
        Objects.requireNonNull(requiredCapabilities, "requiredCapabilities").ifPresent(value -> {
            if (!authorization.effectiveCapabilities().includes(value)) {
                throw failure(
                        LarkProviderErrorCode.PERMISSION_DENIED,
                        "Lark authorization does not include the fixed operation capability",
                        "LARK_OPERATION_CAPABILITY_DENIED");
            }
        });
        UtcTimestamp now = timeProvider.now();
        Connection connection = currentConnection(authorization, now);
        ConnectionGrant grant = currentGrant(authorization, connection, now);
        CredentialAccessContext access = new CredentialAccessContext(
                authorization.organizationId(), required.actor(),
                Set.of(connection.credentialId()), purpose);
        CredentialReference reference = new CredentialReference(
                authorization.organizationId(), connection.credentialId());
        CredentialDescriptor descriptor = describe(reference, access)
                .filter(value -> usableCredential(value, connection, now))
                .orElseThrow(() -> failure(
                        LarkProviderErrorCode.CREDENTIAL_UNAVAILABLE,
                        "Lark application credential is unavailable",
                        "LARK_CREDENTIAL_UNAVAILABLE"));
        validateSubject(descriptor, connection);
        LarkCredentialHandle handle = new LarkCredentialHandle(
                connection.id(), descriptor.version(), descriptor.secretVersion(), now,
                effectiveTtl, timeProvider,
                () -> resolve(reference, access, descriptor, connection));
        return new AuthorizedLarkAccess(
                new LarkTokenCacheKey(
                        authorization.organizationId(), connection.id(), connection.version(),
                        grant.id(), grant.version(), connection.credentialId(),
                        descriptor.version(), descriptor.secretVersion(),
                        authorization.expectedTenantKey()),
                handle);
    }

    private Connection currentConnection(
            LarkConnectionAuthorization authorization, UtcTimestamp now) {
        try {
            return connections.findById(authorization.organizationId(), authorization.connectionId())
                    .filter(value -> value.version() == authorization.connectionVersion())
                    .filter(value -> CONNECTOR_KEY.equals(value.connectorKey()))
                    .filter(value -> value.isUsableAt(now))
                    .orElseThrow(() -> failure(
                            LarkProviderErrorCode.CONNECTION_UNAVAILABLE,
                            "Lark Connection is unavailable",
                            "LARK_CONNECTION_UNAVAILABLE"));
        } catch (LarkProviderException failure) {
            throw failure;
        } catch (RuntimeException ignored) {
            throw failure(
                    LarkProviderErrorCode.CONNECTION_UNAVAILABLE,
                    "Lark Connection could not be revalidated",
                    "LARK_CONNECTION_REVALIDATION_FAILED");
        }
    }

    private ConnectionGrant currentGrant(
            LarkConnectionAuthorization authorization,
            Connection connection,
            UtcTimestamp now) {
        try {
            return grants.findById(authorization.organizationId(), authorization.grantId())
                    .filter(value -> value.version() == authorization.grantVersion())
                    .filter(value -> value.connectionId().equals(connection.id()))
                    .filter(value -> value.connectionOwner().equals(connection.owner()))
                    .filter(value -> value.grantee().organizationId()
                            .equals(authorization.organizationId()))
                    .filter(value -> value.grantee().teamId()
                            .filter(authorization.teamId()::equals).isPresent())
                    .filter(value -> value.grantedAccess().capabilities()
                            .includes(authorization.effectiveCapabilities()))
                    .filter(value -> value.effectiveAccess(
                            value.grantedAccess(), connection, now).isPresent())
                    .orElseThrow(() -> failure(
                            LarkProviderErrorCode.CONNECTION_UNAVAILABLE,
                            "Lark Connection Grant is unavailable",
                            "LARK_GRANT_UNAVAILABLE"));
        } catch (LarkProviderException failure) {
            throw failure;
        } catch (RuntimeException ignored) {
            throw failure(
                    LarkProviderErrorCode.CONNECTION_UNAVAILABLE,
                    "Lark Connection Grant could not be revalidated",
                    "LARK_GRANT_REVALIDATION_FAILED");
        }
    }

    private Optional<CredentialDescriptor> describe(
            CredentialReference reference, CredentialAccessContext access) {
        try {
            return credentials.describe(reference, access);
        } catch (RuntimeException ignored) {
            throw failure(
                    LarkProviderErrorCode.CREDENTIAL_UNAVAILABLE,
                    "Lark application credential metadata is unavailable",
                    "LARK_CREDENTIAL_METADATA_UNAVAILABLE");
        }
    }

    private ResolvedCredential resolve(
            CredentialReference reference,
            CredentialAccessContext access,
            CredentialDescriptor expected,
            Connection connection) {
        Optional<ResolvedCredential> candidate;
        try {
            candidate = credentials.resolve(reference, access);
        } catch (RuntimeException ignored) {
            throw failure(
                    LarkProviderErrorCode.CREDENTIAL_UNAVAILABLE,
                    "Lark application credential could not be resolved",
                    "LARK_CREDENTIAL_RESOLUTION_FAILED");
        }
        if (candidate.isEmpty()) {
            throw failure(
                    LarkProviderErrorCode.CREDENTIAL_UNAVAILABLE,
                    "Lark application credential is unavailable",
                    "LARK_CREDENTIAL_UNAVAILABLE");
        }
        ResolvedCredential resolved = candidate.orElseThrow();
        UtcTimestamp now = timeProvider.now();
        if (resolved.descriptor().version() != expected.version()
                || resolved.descriptor().secretVersion() != expected.secretVersion()
                || !usableCredential(resolved.descriptor(), connection, now)) {
            resolved.close();
            throw failure(
                    LarkProviderErrorCode.CREDENTIAL_UNAVAILABLE,
                    "Lark application credential changed before use",
                    "LARK_CREDENTIAL_CHANGED");
        }
        validateSubject(resolved.descriptor(), connection);
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

    private static void validateSubject(
            CredentialDescriptor descriptor, Connection connection) {
        boolean valid = switch (connection.owner().type()) {
            case ORGANIZATION -> descriptor.subject().type() == CredentialSubjectType.ORGANIZATION;
            case TEAM -> descriptor.subject().type() == CredentialSubjectType.TEAM
                    && descriptor.subject().subjectId().equals(connection.owner().ownerId());
            case USER -> descriptor.subject().type() == CredentialSubjectType.PRINCIPAL
                    && descriptor.subject().subjectId().equals(connection.owner().ownerId());
        };
        if (!valid) {
            throw failure(
                    LarkProviderErrorCode.IDENTITY_MISMATCH,
                    "Lark credential subject does not match the Connection owner",
                    "LARK_CREDENTIAL_SUBJECT_MISMATCH");
        }
    }

    private static Duration requireTtl(Duration value) {
        Duration required = Objects.requireNonNull(value, "handleTimeToLive");
        if (required.isZero() || required.isNegative()
                || required.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("Lark credential handle TTL must be within (0, 5m]");
        }
        return required;
    }

    private static LarkProviderException failure(
            LarkProviderErrorCode code, String message, String evidence) {
        return LarkProviderException.of(code, message, evidence);
    }
}
