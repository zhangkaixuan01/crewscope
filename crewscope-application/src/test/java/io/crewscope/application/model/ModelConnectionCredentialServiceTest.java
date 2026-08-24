package io.crewscope.application.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.credential.CredentialAccessContext;
import io.crewscope.application.credential.CredentialCreateRequest;
import io.crewscope.application.credential.CredentialDescriptor;
import io.crewscope.application.credential.CredentialMutationContext;
import io.crewscope.application.credential.CredentialReference;
import io.crewscope.application.credential.CredentialRevocationReason;
import io.crewscope.application.credential.CredentialSecret;
import io.crewscope.application.credential.CredentialStatus;
import io.crewscope.application.credential.CredentialStore;
import io.crewscope.application.credential.CredentialSubject;
import io.crewscope.application.credential.ResolvedCredential;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.CommandReservation;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.model.ModelAdapterKey;
import io.crewscope.domain.model.ModelBillingSubject;
import io.crewscope.domain.model.ModelConnection;
import io.crewscope.domain.model.ModelConnectionId;
import io.crewscope.domain.model.ModelConnectionOwner;
import io.crewscope.domain.model.ModelConnectionRevocationReason;
import io.crewscope.domain.model.ModelDataPolicy;
import io.crewscope.domain.model.ModelEndpoint;
import io.crewscope.domain.model.ModelProviderDefinition;
import io.crewscope.domain.model.ModelProviderKey;
import io.crewscope.domain.model.ModelRegion;
import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.id.CredentialId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** M5-I02 lifecycle, revocation and plaintext-boundary contract tests. */
class ModelConnectionCredentialServiceTest {

    private static final OrganizationId ORGANIZATION_ID = OrganizationId.generate();
    private static final PrincipalId ACTOR_ID = PrincipalId.generate();
    private static final ModelProviderKey PROVIDER_KEY = new ModelProviderKey("deepseek");
    private static final ModelRegion REGION = new ModelRegion("global");
    private static final String FIRST_SECRET = "m5-i02-secret-one";
    private static final String SECOND_SECRET = "m5-i02-secret-two";

    private final MutableTime time = new MutableTime(Instant.parse("2026-08-23T08:00:00Z"));
    private final InMemoryConnectionRepository connections = new InMemoryConnectionRepository();
    private final InMemoryCredentialStore credentials = new InMemoryCredentialStore(time);
    private final List<DomainEventEnvelope<? extends DomainEvent>> events = new ArrayList<>();
    private final ModelProviderDefinition provider = ModelProviderDefinition.publish(
            PROVIDER_KEY,
            "DeepSeek",
            new ModelAdapterKey("deepseek"),
            new ModelEndpoint("https://api.deepseek.com"),
            java.util.Set.of(REGION),
            ModelDataPolicy.noRetention(),
            ACTOR_ID,
            time.now());
    private final SingleProviderRepository providers = new SingleProviderRepository(provider);

    private ModelConnectionCredentialService service;

    @BeforeEach
    void setUp() {
        DomainEventStore eventStore = events::add;
        OutboxRepository outbox = ignored -> {};
        ModelProviderHealthProbe probe = (definition, connection, handle) -> handle.useSecret(bytes -> {
            assertArrayEquals(SECOND_SECRET.getBytes(StandardCharsets.UTF_8), bytes);
            return ModelProviderHealthProbe.ProbeResult.success();
        });
        service = new ModelConnectionCredentialService(
                connections,
                providers,
                credentials,
                probe,
                eventStore,
                outbox,
                new TransactionExecutor() {
                    @Override
                    public <T> T required(Supplier<T> operation) {
                        return operation.get();
                    }
                },
                time,
                Duration.ofSeconds(30));
    }

    @Test
    void createsRotatesVerifiesAndRevokesWithoutReturningOrAuditingPlaintext() {
        ModelConnection connection = create(FIRST_SECRET);
        ProviderCredentialHandle oldHandle = openHandle(connection);
        assertEquals(FIRST_SECRET, oldHandle.useSecret(bytes -> new String(bytes, StandardCharsets.UTF_8)));

        time.advance(Duration.ofSeconds(1));
        ModelConnection rotated = service.rotate(
                command(connection), CredentialSecret.utf8(SECOND_SECRET));
        assertEquals(1, rotated.credentialBinding().credentialVersion().value());
        assertThrows(
                ModelConnectionCredentialException.class,
                () -> oldHandle.useSecret(bytes -> bytes.length));

        ProviderCredentialHandle currentHandle = openHandle(rotated);
        assertEquals(SECOND_SECRET, currentHandle.useSecret(bytes -> new String(bytes, StandardCharsets.UTF_8)));
        currentHandle.close();
        assertThrows(IllegalStateException.class, () -> currentHandle.useSecret(bytes -> bytes.length));

        time.advance(Duration.ofSeconds(1));
        ModelConnection verified = service.verify(command(rotated));
        assertTrue(verified.health().isHealthyFor(verified.credentialBinding().credentialVersion()));

        time.advance(Duration.ofSeconds(1));
        RevokeModelConnectionCredentialCommand revoke = new RevokeModelConnectionCredentialCommand(
                ORGANIZATION_ID,
                verified.id(),
                verified.version(),
                verified.credentialBinding().credentialVersion(),
                CredentialRevocationReason.CONNECTION_REVOKED,
                ModelConnectionRevocationReason.OWNER_REQUESTED,
                ACTOR_ID,
                UUID.randomUUID());
        ModelConnection revoked = service.revoke(revoke);
        assertTrue(revoked.status().isTerminal());
        assertThrows(RuntimeException.class, () -> service.revoke(revoke));

        String auditText = events.toString();
        assertFalse(auditText.contains(FIRST_SECRET));
        assertFalse(auditText.contains(SECOND_SECRET));
        assertTrue(auditText.contains("CREDENTIAL_ROTATED"));
        assertTrue(auditText.contains("HANDLE_ISSUED"));
        assertTrue(auditText.contains("VERIFIED"));
        assertTrue(auditText.contains("REVOKED"));
    }

    @Test
    void expiresHandlesAndKeepsKmsEnvelopeVersionIndependentFromSecretVersion() {
        ModelConnection connection = create(FIRST_SECRET);
        ProviderCredentialHandle handle = openHandle(connection);

        time.advance(Duration.ofSeconds(30));
        assertThrows(IllegalStateException.class, () -> handle.useSecret(bytes -> bytes.length));
        assertTrue(handle.isClosed());

        CredentialDescriptor descriptor = credentials.descriptor(connection);
        credentials.rewrap(connection);
        CredentialDescriptor rewrapped = credentials.descriptor(connection);
        assertEquals(descriptor.secretVersion(), rewrapped.secretVersion());
        assertEquals(descriptor.version() + 1, rewrapped.version());
    }

    @Test
    void invalidatesAnIssuedHandleWhenTheConnectionIsSuspended() {
        ModelConnection connection = create(FIRST_SECRET);
        ProviderCredentialHandle handle = openHandle(connection);
        connections.update(connection.suspend(connection.version(), ACTOR_ID, time.now()));

        ModelConnectionCredentialException failure = assertThrows(
                ModelConnectionCredentialException.class,
                () -> handle.useSecret(bytes -> bytes.length));

        assertEquals(
                ModelConnectionCredentialException.Error.CREDENTIAL_UNAVAILABLE,
                failure.error());
    }

    @Test
    void invalidatesAnIssuedHandleWhenTheProviderIsDisabled() {
        ModelConnection connection = create(FIRST_SECRET);
        ProviderCredentialHandle handle = openHandle(connection);
        providers.replace(provider.disable(ACTOR_ID, time.now()));

        ModelConnectionCredentialException failure = assertThrows(
                ModelConnectionCredentialException.class,
                () -> handle.useSecret(bytes -> bytes.length));

        assertEquals(
                ModelConnectionCredentialException.Error.CREDENTIAL_UNAVAILABLE,
                failure.error());
    }

    @Test
    void rejectsAHandleWhenTheConnectionVersionChangedAfterPreflight() {
        ModelConnection preflighted = create(FIRST_SECRET);
        connections.update(preflighted.suspend(preflighted.version(), ACTOR_ID, time.now()));

        assertThrows(
                OptimisticLockConflictException.class,
                () -> service.openHandle(new OpenProviderCredentialHandleRequest(
                        ORGANIZATION_ID,
                        preflighted.id(),
                        preflighted.version(),
                        preflighted.credentialBinding().credentialVersion(),
                        ACTOR_ID,
                        "model:stale-preflight",
                        UUID.randomUUID())));
    }

    @Test
    void returnsACompletedVerifyReplayBeforeStaleVersionChecksOrProviderCalls() {
        ModelConnection connection = create(FIRST_SECRET);
        CommandReceipt receipt = new CommandReceipt(
                UUID.randomUUID(), UUID.randomUUID(), connection.version() + 1, UUID.randomUUID());
        int eventCount = events.size();

        var replay = service.verify(command(connection), new ModelConnectionLifecycleCommandGate() {
            @Override
            public Optional<CommandReceipt> findCompletedReplay() {
                return Optional.of(receipt);
            }

            @Override
            public CommandReservation reserve(UtcTimestamp occurredAt) {
                throw new AssertionError("a completed replay must not reserve again");
            }

            @Override
            public CommandReceipt complete(
                    UUID domainEventId, long committedVersion, UtcTimestamp occurredAt) {
                throw new AssertionError("a completed replay must not commit again");
            }
        });

        assertTrue(replay.replayed());
        assertEquals(receipt, replay.receipt());
        assertEquals(eventCount, events.size());
    }

    private ModelConnection create(String secretText) {
        ModelConnectionId connectionId = ModelConnectionId.generate();
        CredentialId credentialId = CredentialId.generate();
        CreateModelConnectionCredentialCommand command = new CreateModelConnectionCredentialCommand(
                connectionId,
                PROVIDER_KEY,
                ModelConnectionOwner.organization(ORGANIZATION_ID),
                provider.defaultEndpoint(),
                REGION,
                ModelBillingSubject.organization(ORGANIZATION_ID),
                credentialId,
                CredentialSubject.organization(ORGANIZATION_ID),
                "deepseek-" + connectionId,
                Map.of("environment", "test"),
                Optional.empty(),
                ACTOR_ID,
                UUID.randomUUID());
        return service.create(command, CredentialSecret.utf8(secretText));
    }

    private ProviderCredentialHandle openHandle(ModelConnection connection) {
        return service.openHandle(new OpenProviderCredentialHandleRequest(
                ORGANIZATION_ID,
                connection.id(),
                connection.version(),
                connection.credentialBinding().credentialVersion(),
                ACTOR_ID,
                "model:test",
                UUID.randomUUID()));
    }

    private static ModelConnectionCredentialCommand command(ModelConnection connection) {
        return new ModelConnectionCredentialCommand(
                ORGANIZATION_ID,
                connection.id(),
                connection.version(),
                connection.credentialBinding().credentialVersion(),
                ACTOR_ID,
                UUID.randomUUID());
    }

    private static final class InMemoryConnectionRepository implements ModelConnectionRepository {
        private final Map<ModelConnectionId, ModelConnection> values = new HashMap<>();

        @Override
        public ModelConnection register(ModelConnection connection) {
            values.put(connection.id(), connection);
            return connection;
        }

        @Override
        public ModelConnection update(ModelConnection connection) {
            values.put(connection.id(), connection);
            return connection;
        }

        @Override
        public Optional<ModelConnection> findById(
                OrganizationId organizationId, ModelConnectionId connectionId) {
            return Optional.ofNullable(values.get(connectionId))
                    .filter(value -> value.organizationId().equals(organizationId));
        }

        @Override
        public List<ModelConnection> findByOwner(ModelConnectionOwner owner) {
            return values.values().stream().filter(value -> value.owner().equals(owner)).toList();
        }

        @Override
        public List<ModelConnection> findByOwner(
                ModelConnectionOwner owner, int offset, int limit) {
            return findByOwner(owner).stream().skip(offset).limit(limit).toList();
        }
    }

    private static final class SingleProviderRepository
            implements ModelProviderDefinitionRepository {
        private ModelProviderDefinition provider;

        private SingleProviderRepository(ModelProviderDefinition provider) {
            this.provider = provider;
        }

        @Override
        public ModelProviderDefinition register(ModelProviderDefinition definition) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ModelProviderDefinition updateLifecycle(ModelProviderDefinition definition) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ModelProviderDefinition> findByKey(ModelProviderKey providerKey) {
            return provider.providerKey().equals(providerKey) ? Optional.of(provider) : Optional.empty();
        }

        @Override
        public List<ModelProviderDefinition> findPage(int offset, int limit) {
            return List.of(provider);
        }

        private void replace(ModelProviderDefinition replacement) {
            provider = replacement;
        }
    }

    private static final class InMemoryCredentialStore implements CredentialStore {
        private final MutableTime time;
        private final Map<CredentialReference, StoredCredential> values = new HashMap<>();

        private InMemoryCredentialStore(MutableTime time) {
            this.time = time;
        }

        @Override
        public CredentialDescriptor create(CredentialCreateRequest request, CredentialSecret secret) {
            CredentialDescriptor descriptor = descriptor(request, CredentialStatus.ACTIVE, 0, 0, Optional.empty());
            values.put(descriptor.reference(), new StoredCredential(descriptor, secret.copyBytes()));
            return descriptor;
        }

        @Override
        public Optional<CredentialDescriptor> describe(
                CredentialReference reference, CredentialAccessContext accessContext) {
            return accessContext.allows(reference)
                    ? Optional.ofNullable(values.get(reference)).map(StoredCredential::descriptor)
                    : Optional.empty();
        }

        @Override
        public Optional<ResolvedCredential> resolve(
                CredentialReference reference, CredentialAccessContext accessContext) {
            StoredCredential stored = values.get(reference);
            if (stored == null
                    || !accessContext.allows(reference)
                    || !stored.descriptor().isUsableAt(time.now())) {
                return Optional.empty();
            }
            return Optional.of(new ResolvedCredential(
                    stored.descriptor(), CredentialSecret.of(stored.secret())));
        }

        @Override
        public CredentialDescriptor rotate(
                CredentialReference reference,
                long expectedVersion,
                CredentialMutationContext mutationContext,
                CredentialSecret newSecret) {
            StoredCredential stored = values.get(reference);
            if (stored.descriptor().version() != expectedVersion) {
                throw new IllegalStateException("stale test credential");
            }
            CredentialDescriptor current = stored.descriptor();
            CredentialDescriptor next = copy(
                    current,
                    CredentialStatus.ACTIVE,
                    current.version() + 1,
                    current.secretVersion() + 1,
                    Optional.empty());
            Arrays.fill(stored.secret(), (byte) 0);
            values.put(reference, new StoredCredential(next, newSecret.copyBytes()));
            return next;
        }

        @Override
        public CredentialDescriptor revoke(
                CredentialReference reference,
                long expectedVersion,
                CredentialMutationContext mutationContext,
                CredentialRevocationReason reason) {
            StoredCredential stored = values.get(reference);
            if (stored.descriptor().version() != expectedVersion) {
                throw new IllegalStateException("stale test credential");
            }
            CredentialDescriptor current = stored.descriptor();
            CredentialDescriptor revoked = copy(
                    current,
                    CredentialStatus.REVOKED,
                    current.version() + 1,
                    current.secretVersion(),
                    Optional.of(time.now()));
            Arrays.fill(stored.secret(), (byte) 0);
            values.put(reference, new StoredCredential(revoked, new byte[] {0}));
            return revoked;
        }

        private CredentialDescriptor descriptor(ModelConnection connection) {
            return values.get(new CredentialReference(
                    connection.organizationId(), connection.credentialBinding().credentialId()))
                    .descriptor();
        }

        private void rewrap(ModelConnection connection) {
            CredentialDescriptor current = descriptor(connection);
            StoredCredential stored = values.get(current.reference());
            values.put(current.reference(), new StoredCredential(
                    copy(
                            current,
                            current.status(),
                            current.version() + 1,
                            current.secretVersion(),
                            current.revokedAt()),
                    stored.secret()));
        }

        private CredentialDescriptor descriptor(
                CredentialCreateRequest request,
                CredentialStatus status,
                long version,
                long secretVersion,
                Optional<UtcTimestamp> revokedAt) {
            return new CredentialDescriptor(
                    request.credentialId(), request.subject(), request.credentialKey(),
                    request.providerKey(), request.connectionRef(), request.credentialType(),
                    request.metadata(), status, request.expiresAt(), Optional.empty(), revokedAt,
                    "test-key", "AES-256-GCM", "1", request.createdBy(), request.createdBy(),
                    time.now(), time.now(), version, secretVersion);
        }

        private CredentialDescriptor copy(
                CredentialDescriptor value,
                CredentialStatus status,
                long version,
                long secretVersion,
                Optional<UtcTimestamp> revokedAt) {
            return new CredentialDescriptor(
                    value.credentialId(), value.subject(), value.credentialKey(), value.providerKey(),
                    value.connectionRef(), value.credentialType(), value.metadata(), status,
                    value.expiresAt(), status == CredentialStatus.ACTIVE && secretVersion > 0
                            ? Optional.of(time.now()) : value.rotatedAt(),
                    revokedAt, value.keyId(), value.algorithm(), value.aadVersion(), value.createdBy(),
                    ACTOR_ID, value.createdAt(), time.now(), version, secretVersion);
        }

        private record StoredCredential(CredentialDescriptor descriptor, byte[] secret) {}
    }

    private static final class MutableTime implements TimeProvider {
        private Instant instant;

        private MutableTime(Instant instant) {
            this.instant = instant;
        }

        @Override
        public UtcTimestamp now() {
            return UtcTimestamp.from(instant);
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }
    }
}
