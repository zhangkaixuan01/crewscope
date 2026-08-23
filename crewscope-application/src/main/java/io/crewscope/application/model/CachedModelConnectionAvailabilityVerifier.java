package io.crewscope.application.model;

import io.crewscope.application.credential.CredentialAccessContext;
import io.crewscope.application.credential.CredentialDescriptor;
import io.crewscope.application.credential.CredentialReference;
import io.crewscope.application.credential.CredentialStore;
import io.crewscope.domain.agent.AgentModelPreflightException;
import io.crewscope.domain.agent.AgentModelPreflightRejectionCode;
import io.crewscope.domain.model.ModelConnection;
import io.crewscope.domain.model.ModelConnectionHealthStatus;
import io.crewscope.domain.model.ModelConnectionId;
import io.crewscope.domain.model.ModelConnectionStatus;
import io.crewscope.domain.model.ModelCredentialVersion;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Bounded short-TTL cache for exact connection and credential-version availability decisions. */
public final class CachedModelConnectionAvailabilityVerifier
        implements ModelConnectionAvailabilityVerifier {

    private final CredentialStore credentialStore;
    private final Duration timeToLive;
    private final int maximumEntries;
    private final Map<CacheKey, CacheEntry> cache = new LinkedHashMap<>(16, 0.75f, true);

    public CachedModelConnectionAvailabilityVerifier(
            CredentialStore credentialStore, Duration timeToLive, int maximumEntries) {
        this.credentialStore = Objects.requireNonNull(credentialStore, "credentialStore");
        this.timeToLive = requireTimeToLive(timeToLive);
        if (maximumEntries < 1 || maximumEntries > 100_000) {
            throw new IllegalArgumentException("maximumEntries must be between 1 and 100000");
        }
        this.maximumEntries = maximumEntries;
    }

    @Override
    public void requireAvailable(
            ModelConnection connection,
            PrincipalId requestingPrincipalId,
            UtcTimestamp checkedAt) {
        ModelConnection required = Objects.requireNonNull(connection, "connection");
        PrincipalId actor = Objects.requireNonNull(requestingPrincipalId, "requestingPrincipalId");
        UtcTimestamp now = Objects.requireNonNull(checkedAt, "checkedAt");
        requirePersistedHealth(required);
        CacheKey key = new CacheKey(
                required.organizationId(),
                actor,
                required.id(),
                required.version(),
                required.credentialBinding().credentialVersion());
        CacheEntry cached = get(key, now.value());
        if (cached != null) {
            cached.requireAvailable();
            return;
        }

        CacheEntry loaded = load(required, actor, now);
        put(key, loaded, now.value());
        loaded.requireAvailable();
    }

    @Override
    public synchronized void invalidate(
            OrganizationId organizationId, ModelConnectionId connectionId) {
        OrganizationId organization = Objects.requireNonNull(organizationId, "organizationId");
        ModelConnectionId id = Objects.requireNonNull(connectionId, "connectionId");
        cache.keySet().removeIf(key -> key.organizationId().equals(organization)
                && key.connectionId().equals(id));
    }

    public synchronized int cacheSize() {
        return cache.size();
    }

    private CacheEntry load(
            ModelConnection connection, PrincipalId actor, UtcTimestamp checkedAt) {
        CredentialReference reference = new CredentialReference(
                connection.organizationId(), connection.credentialBinding().credentialId());
        CredentialAccessContext access = new CredentialAccessContext(
                connection.organizationId(),
                actor,
                java.util.Set.of(connection.credentialBinding().credentialId()),
                "model:execution-preflight");
        CredentialDescriptor descriptor = credentialStore.describe(reference, access).orElse(null);
        Instant cacheDeadline = checkedAt.value().plus(timeToLive);
        if (descriptor == null || !matches(connection, descriptor)) {
            return CacheEntry.rejected(cacheDeadline);
        }
        Instant validityDeadline = descriptor.expiresAt()
                .map(UtcTimestamp::value)
                .filter(value -> value.isBefore(cacheDeadline))
                .orElse(cacheDeadline);
        if (!descriptor.isUsableAt(checkedAt)) {
            return CacheEntry.rejected(validityDeadline);
        }
        return CacheEntry.available(validityDeadline);
    }

    private static boolean matches(ModelConnection connection, CredentialDescriptor descriptor) {
        return descriptor.credentialId().equals(connection.credentialBinding().credentialId())
                && credentialSubjectMatches(connection, descriptor)
                && descriptor.providerKey().equals(connection.providerKey().toString())
                && descriptor.connectionRef().filter(connection.id().value()::equals).isPresent()
                && descriptor.credentialType().equals(ModelConnectionCredentialService.API_KEY_CREDENTIAL_TYPE)
                && descriptor.secretVersion()
                        == connection.credentialBinding().credentialVersion().value();
    }

    private static boolean credentialSubjectMatches(
            ModelConnection connection, CredentialDescriptor descriptor) {
        var expected = connection.credentialBinding().subject();
        var actual = descriptor.subject();
        if (!actual.organizationId().equals(expected.organizationId())) {
            return false;
        }
        return switch (expected.type()) {
            case ORGANIZATION -> actual.type()
                    == io.crewscope.application.credential.CredentialSubjectType.ORGANIZATION;
            case TEAM -> actual.type()
                            == io.crewscope.application.credential.CredentialSubjectType.TEAM
                    && actual.teamId().equals(expected.teamId());
            case PRINCIPAL -> actual.type()
                            == io.crewscope.application.credential.CredentialSubjectType.PRINCIPAL
                    && actual.principalId().equals(expected.principalId());
        };
    }

    private static void requirePersistedHealth(ModelConnection connection) {
        if (connection.status() != ModelConnectionStatus.ACTIVE
                || connection.health().status() != ModelConnectionHealthStatus.HEALTHY
                || !connection.health().isHealthyFor(
                        connection.credentialBinding().credentialVersion())) {
            throw rejected(AgentModelPreflightRejectionCode.CONNECTION_UNAVAILABLE);
        }
    }

    private synchronized CacheEntry get(CacheKey key, Instant now) {
        evictExpired(now);
        return cache.get(key);
    }

    private synchronized void put(CacheKey key, CacheEntry value, Instant now) {
        evictExpired(now);
        cache.put(key, value);
        while (cache.size() > maximumEntries) {
            Iterator<CacheKey> oldest = cache.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
    }

    private void evictExpired(Instant now) {
        cache.entrySet().removeIf(entry -> !entry.getValue().validUntil().isAfter(now));
    }

    private static Duration requireTimeToLive(Duration value) {
        Duration required = Objects.requireNonNull(value, "timeToLive");
        if (required.isZero()
                || required.isNegative()
                || required.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("timeToLive must be positive and at most 5 minutes");
        }
        return required;
    }

    private static AgentModelPreflightException rejected(
            AgentModelPreflightRejectionCode reason) {
        return new AgentModelPreflightException(reason);
    }

    private record CacheKey(
            OrganizationId organizationId,
            PrincipalId requestingPrincipalId,
            ModelConnectionId connectionId,
            long connectionVersion,
            ModelCredentialVersion credentialVersion) {}

    private record CacheEntry(boolean available, Instant validUntil) {

        private static CacheEntry available(Instant validUntil) {
            return new CacheEntry(true, validUntil);
        }

        private static CacheEntry rejected(Instant validUntil) {
            return new CacheEntry(false, validUntil);
        }

        private void requireAvailable() {
            if (!available) {
                throw CachedModelConnectionAvailabilityVerifier.rejected(
                        AgentModelPreflightRejectionCode.CREDENTIAL_UNAVAILABLE);
            }
        }
    }
}
