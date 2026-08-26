package io.crewscope.integration.provider.collaboration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
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
import io.crewscope.application.provider.ConnectionGrantRepository;
import io.crewscope.application.provider.ConnectionRepository;
import io.crewscope.domain.collaboration.LarkCollaborationCapabilities;
import io.crewscope.domain.collaboration.LarkConnectionAuthorization;
import io.crewscope.domain.collaboration.LarkExternalTenant;
import io.crewscope.domain.collaboration.LarkOpenId;
import io.crewscope.domain.collaboration.LarkTenantKey;
import io.crewscope.application.collaboration.LarkProviderHealthStatus;
import io.crewscope.domain.provider.Connection;
import io.crewscope.domain.provider.ConnectionGrant;
import io.crewscope.domain.provider.ConnectionGrantId;
import io.crewscope.domain.provider.ConnectionGrantStatus;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.provider.ConnectionStatus;
import io.crewscope.domain.provider.ProviderAccessScope;
import io.crewscope.domain.provider.ProviderOwner;
import io.crewscope.domain.provider.ProviderOwnerType;
import io.crewscope.domain.provider.ProviderResourceScope;
import io.crewscope.domain.provider.ProviderType;
import io.crewscope.domain.provider.ProviderConnectionRequirement;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.id.CredentialId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** M6-I04 production Connector tests against an exact loopback OpenAPI substitute. */
class LarkConnectorM6I04IntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-26T08:00:00Z");
    private static final String APP_A = "cli_m6i04_a";
    private static final String APP_B = "cli_m6i04_b";
    private static final String SECRET_MARKER = "m6i04-secret-marker";

    @Test
    void isolatesTenantTokensRefreshesOnlyThe401KeyAndUsesSingleFlight() throws Exception {
        try (LarkStub stub = LarkStub.start(); Fixture fixture = new Fixture(stub.baseUri())) {
            assertEquals("tenant-a", fixture.clientA.queryTenant(fixture.contextA)
                    .tenantKey().value());
            assertEquals("tenant-a", fixture.clientA.queryTenant(fixture.contextA)
                    .tenantKey().value());
            assertEquals("tenant-b", fixture.clientA.queryTenant(fixture.contextB)
                    .tenantKey().value());
            assertEquals(1, stub.tokenRequests(APP_A));
            assertEquals(1, stub.tokenRequests(APP_B));

            stub.rejectNextTenantA.set(1);
            fixture.clientA.queryTenant(fixture.contextA);
            assertEquals(2, stub.tokenRequests(APP_A));
            assertEquals(1, stub.tokenRequests(APP_B));

            stub.rejectNextTenantA.set(2);
            assertCode(LarkProviderErrorCode.AUTHENTICATION_REQUIRED,
                    () -> fixture.clientA.queryTenant(fixture.contextA));
            assertEquals(3, stub.tokenRequests(APP_A));
            fixture.clientA.queryTenant(fixture.contextA);
            assertEquals(4, stub.tokenRequests(APP_A));

            fixture.rotateSecretA();
            fixture.clientA.queryTenant(fixture.contextA);
            assertEquals(5, stub.tokenRequests(APP_A));

            try (Fixture concurrent = new Fixture(stub.baseUri())) {
                int before = stub.tokenRequests(APP_A);
                ExecutorService executor = Executors.newFixedThreadPool(8);
                try {
                    List<CompletableFuture<Void>> calls = new ArrayList<>();
                    for (int index = 0; index < 8; index++) {
                        calls.add(CompletableFuture.runAsync(
                                () -> concurrent.clientA.queryTenant(concurrent.contextA),
                                executor));
                    }
                    CompletableFuture.allOf(calls.toArray(CompletableFuture[]::new)).join();
                } finally {
                    executor.shutdownNow();
                }
                assertEquals(before + 1, stub.tokenRequests(APP_A));
            }
            assertTrue(fixture.credentials.allResolvedSecretsClosed());
        }
    }

    @Test
    void revalidatesConnectionGrantAndCredentialBeforeUsingACachedToken() throws Exception {
        try (LarkStub stub = LarkStub.start(); Fixture fixture = new Fixture(stub.baseUri())) {
            fixture.clientA.queryTenant(fixture.contextA);
            int requests = stub.totalRequests.get();

            fixture.connections.availableA = false;
            assertCode(LarkProviderErrorCode.CONNECTION_UNAVAILABLE,
                    () -> fixture.clientA.queryTenant(fixture.contextA));
            assertEquals(requests, stub.totalRequests.get());

            fixture.connections.availableA = true;
            fixture.grants.availableA = false;
            assertCode(LarkProviderErrorCode.CONNECTION_UNAVAILABLE,
                    () -> fixture.clientA.queryTenant(fixture.contextA));
            assertEquals(requests, stub.totalRequests.get());

            fixture.grants.availableA = true;
            fixture.credentials.availableA = false;
            assertCode(LarkProviderErrorCode.CREDENTIAL_UNAVAILABLE,
                    () -> fixture.clientA.queryTenant(fixture.contextA));
            assertEquals(requests, stub.totalRequests.get());
        }
    }

    @Test
    void collaborationProviderMapsExactIdentityRateLimitAndCachedTokenRevocation()
            throws Exception {
        try (LarkStub stub = LarkStub.start(); Fixture fixture = new Fixture(stub.baseUri())) {
            TimeProvider time = TimeProvider.from(Clock.fixed(NOW, ZoneOffset.UTC));
            LarkCollaborationProvider provider = new LarkCollaborationProvider(
                    fixture.clientA, time);
            var tenantObservation = provider.verifyTenant(
                    fixture.contextA.authorization(), fixture.actor);
            LarkExternalTenant tenant = LarkExternalTenant.verify(
                    fixture.contextA.authorization(),
                    tenantObservation.tenantKey(),
                    tenantObservation.providerVersion(),
                    tenantObservation.observedAt());
            var member = provider.verifyMember(
                    fixture.contextA.authorization(),
                    tenant,
                    new LarkOpenId("ou_member_a"),
                    fixture.actor);

            assertEquals("ou_member_a", member.openId().value());
            assertEquals(ProviderType.COLLABORATION, provider.descriptor().type());
            assertEquals(ProviderConnectionRequirement.REQUIRED,
                    provider.connectionRequirement());
            assertEquals(LarkCollaborationCapabilities.COMPLETE, provider.capabilities());
            assertFalse((tenantObservation + " " + member).contains("tenant-a"));

            stub.nextStatus.set(429);
            var limited = provider.checkHealth(
                    fixture.contextA.authorization(), fixture.actor);
            assertEquals(LarkProviderHealthStatus.RATE_LIMITED, limited.status());
            assertEquals(Optional.of(Duration.ofSeconds(7)), limited.retryAfter());

            int requests = stub.totalRequests.get();
            fixture.connections.availableA = false;
            var revoked = provider.checkHealth(
                    fixture.contextA.authorization(), fixture.actor);
            assertEquals(LarkProviderHealthStatus.CONNECTION_UNAVAILABLE, revoked.status());
            assertEquals(requests, stub.totalRequests.get());
        }
    }

    @Test
    void executesOnlyFixedMemberAndMessageOperationsAndKeepsEvidenceRedacted() throws Exception {
        try (LarkStub stub = LarkStub.start(); Fixture fixture = new Fixture(stub.baseUri())) {
            var member = fixture.clientA.queryMember(
                    fixture.contextA, new LarkOpenId("ou_member_a"));
            var sent = fixture.clientA.sendTextMessage(
                    fixture.contextA,
                    new LarkTextMessageRequest(
                            new LarkOpenId("ou_member_a"), "Review is ready", UUID.randomUUID()));
            var queried = fixture.clientA.queryMessage(fixture.contextA, sent.messageId());

            assertEquals("ou_member_a", member.openId().value());
            assertEquals("contact-user-open-api-v1", member.providerVersion().value());
            assertEquals(sent.messageId(), queried.messageId());
            assertEquals(List.of(
                    "/open-apis/contact/v3/users/ou_member_a?user_id_type=open_id&department_id_type=open_department_id",
                    "/open-apis/im/v1/messages?receive_id_type=open_id",
                    "/open-apis/im/v1/messages/om_m6i04_message"), stub.businessPaths);
            String evidence = fixture.clientA.safeSummary() + member + sent + queried;
            assertFalse(evidence.contains(SECRET_MARKER));
            assertFalse(evidence.contains("token-m6i04"));
            assertFalse(evidence.contains(stub.baseUri().toString()));
            assertFalse(evidence.contains("ou_member_a"));

            LarkApiCallContext memberOnly = new LarkApiCallContext(
                    new LarkConnectionAuthorization(
                            fixture.contextA.authorization().organizationId(),
                            fixture.contextA.authorization().teamId(),
                            fixture.contextA.authorization().providerBindingId(),
                            fixture.contextA.authorization().providerBindingVersion(),
                            fixture.contextA.authorization().connectionId(),
                            fixture.contextA.authorization().connectionVersion(),
                            fixture.contextA.authorization().grantId(),
                            fixture.contextA.authorization().grantVersion(),
                            fixture.contextA.authorization().expectedTenantKey(),
                            LarkCollaborationCapabilities.MEMBER_MAPPING),
                    fixture.contextA.actor());
            int requests = stub.totalRequests.get();
            assertCode(LarkProviderErrorCode.PERMISSION_DENIED,
                    () -> fixture.clientA.sendTextMessage(
                            memberOnly,
                            new LarkTextMessageRequest(
                                    new LarkOpenId("ou_member_a"), "Review", UUID.randomUUID())));
            assertEquals(requests, stub.totalRequests.get());
        }
    }

    @Test
    void normalizesProviderStatusesRetryAfterTimeoutsAndInvalidResponses() throws Exception {
        try (LarkStub stub = LarkStub.start(); Fixture fixture = new Fixture(stub.baseUri())) {
            fixture.clientA.queryTenant(fixture.contextA);
            Map<Integer, LarkProviderErrorCode> expected = Map.of(
                    403, LarkProviderErrorCode.PERMISSION_DENIED,
                    404, LarkProviderErrorCode.RESOURCE_UNAVAILABLE,
                    429, LarkProviderErrorCode.RATE_LIMITED,
                    500, LarkProviderErrorCode.PROVIDER_UNAVAILABLE,
                    503, LarkProviderErrorCode.PROVIDER_UNAVAILABLE);
            for (var entry : expected.entrySet()) {
                stub.nextStatus.set(entry.getKey());
                LarkProviderException failure = assertThrows(
                        LarkProviderException.class,
                        () -> fixture.clientA.queryTenant(fixture.contextA));
                assertEquals(entry.getValue(), failure.code());
                if (entry.getKey() == 429) {
                    assertEquals(Optional.of(Duration.ofSeconds(7)), failure.retryAfter());
                }
                assertRedacted(failure, stub.baseUri());
            }

            stub.invalidTenantResponse = true;
            assertCode(LarkProviderErrorCode.INVALID_RESPONSE,
                    () -> fixture.clientA.queryTenant(fixture.contextA));
            stub.invalidTenantResponse = false;

            stub.invalidJsonResponse = true;
            assertCode(LarkProviderErrorCode.INVALID_RESPONSE,
                    () -> fixture.clientA.queryTenant(fixture.contextA));
            stub.invalidJsonResponse = false;

            stub.oversizedTenantResponse = true;
            assertCode(LarkProviderErrorCode.INVALID_RESPONSE,
                    () -> fixture.smallResponseClient.queryTenant(fixture.contextA));
            stub.oversizedTenantResponse = false;

            stub.delayMillis.set(350);
            assertCode(LarkProviderErrorCode.PROVIDER_UNAVAILABLE,
                    () -> fixture.shortTimeoutClient.queryTenant(fixture.contextA));
            assertCode(LarkProviderErrorCode.UNKNOWN_DELIVERY,
                    () -> fixture.shortTimeoutClient.sendTextMessage(
                            fixture.contextA,
                            new LarkTextMessageRequest(
                                    new LarkOpenId("ou_member_a"), "Review", UUID.randomUUID())));
        }
    }

    @Test
    void preservesInterruptionAndNormalizesCancellationWithoutSensitiveEvidence()
            throws Exception {
        try (LarkStub stub = LarkStub.start(); Fixture fixture = new Fixture(stub.baseUri())) {
            fixture.clientA.queryTenant(fixture.contextA);
            stub.delayMillis.set(5_000);
            CountDownLatch started = stub.armNextBusinessRequest();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread caller = new Thread(() -> {
                try {
                    fixture.clientA.queryTenant(fixture.contextA);
                } catch (Throwable caught) {
                    failure.set(caught);
                }
            }, "lark-m6-i04-interruption-test");
            caller.start();
            assertTrue(started.await(2, TimeUnit.SECONDS));
            caller.interrupt();
            caller.join(2_000);

            assertTrue(failure.get() instanceof LarkProviderException);
            assertEquals(LarkProviderErrorCode.CANCELLED,
                    ((LarkProviderException) failure.get()).code());
            assertTrue(caller.isInterrupted());
            assertRedacted((LarkProviderException) failure.get(), stub.baseUri());
        }
    }

    @Test
    void rejectsEveryUntrustedBaseOriginShape() {
        List<URI> endpoints = List.of(
                URI.create("http://169.254.169.254/latest/meta-data"),
                URI.create("https://user:secret@open.feishu.cn"),
                URI.create("https://evil.invalid"),
                URI.create("https://open.feishu.cn/open-apis"),
                URI.create("https://open.feishu.cn?target=127.0.0.1"),
                URI.create("https://open.feishu.cn#fragment"),
                URI.create("http://127.0.0.1:18080"));
        endpoints.forEach(endpoint -> assertThrows(
                IllegalArgumentException.class,
                () -> LarkEndpointPolicy.requireAllowed(endpoint, false)));
        assertThrows(IllegalArgumentException.class, () -> LarkEndpointPolicy.requireAllowed(
                URI.create("http://localhost:18080"), true));
        assertEquals(LarkEndpointPolicy.PRODUCTION_ORIGIN,
                LarkEndpointPolicy.requireAllowed(LarkEndpointPolicy.PRODUCTION_ORIGIN, false));
    }

    private static void assertCode(LarkProviderErrorCode expected, Runnable operation) {
        assertEquals(expected,
                assertThrows(LarkProviderException.class, operation::run).code());
    }

    private static void assertRedacted(LarkProviderException failure, URI endpoint) {
        String evidence = failure + " " + failure.getMessage();
        assertFalse(evidence.contains(SECRET_MARKER));
        assertFalse(evidence.contains("token-m6i04"));
        assertFalse(evidence.contains(endpoint.toString()));
        assertFalse(evidence.contains("provider-internal-body"));
    }

    private static final class Fixture implements AutoCloseable {
        private final UtcTimestamp now = UtcTimestamp.from(NOW);
        private final OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
        private final TeamId teamId = new TeamId(UUID.randomUUID());
        private final PrincipalId actor = new PrincipalId(UUID.randomUUID());
        private final Connection connectionA;
        private final Connection connectionB;
        private final ConnectionGrant grantA;
        private final ConnectionGrant grantB;
        private final MutableConnections connections;
        private final MutableGrants grants;
        private final MutableCredentials credentials;
        private final LarkTenantTokenCache tokenCache =
                new LarkTenantTokenCache(32, Duration.ofSeconds(60));
        private final LarkOpenApiClient clientA;
        private final LarkOpenApiClient shortTimeoutClient;
        private final LarkOpenApiClient smallResponseClient;
        private final LarkApiCallContext contextA;
        private final LarkApiCallContext contextB;

        private Fixture(URI baseUri) {
            ProviderOwner owner = new ProviderOwner(
                    organizationId, ProviderOwnerType.TEAM, teamId.value(),
                    Optional.of(teamId), Optional.empty());
            connectionA = connection(owner, "tenant-a");
            connectionB = connection(owner, "tenant-b");
            ProviderAccessScope scope = new ProviderAccessScope(
                    LarkCollaborationCapabilities.COMPLETE,
                    ProviderResourceScope.allResources());
            grantA = grant(connectionA, owner, scope);
            grantB = grant(connectionB, owner, scope);
            connections = new MutableConnections(connectionA, connectionB);
            grants = new MutableGrants(grantA, grantB);
            credentials = new MutableCredentials(
                    organizationId, teamId, actor, now, connectionA, connectionB);
            TimeProvider time = TimeProvider.from(Clock.fixed(NOW, ZoneOffset.UTC));
            LarkCredentialAccessManager accessManager = new LarkCredentialAccessManager(
                    connections, grants, credentials, time, Duration.ofSeconds(30));
            HttpClient http = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NEVER).build();
            clientA = new LarkOpenApiClient(
                    http, new ObjectMapper(), baseUri, true, Duration.ofSeconds(2), 1_048_576,
                    time, accessManager, tokenCache);
            shortTimeoutClient = new LarkOpenApiClient(
                    http, new ObjectMapper(), baseUri, true, Duration.ofMillis(100), 1_048_576,
                    time, accessManager, tokenCache);
            smallResponseClient = new LarkOpenApiClient(
                    http, new ObjectMapper(), baseUri, true, Duration.ofSeconds(2), 1_024,
                    time, accessManager, tokenCache);
            contextA = context(connectionA, grantA, "tenant-a");
            contextB = context(connectionB, grantB, "tenant-b");
        }

        private Connection connection(ProviderOwner owner, String externalReference) {
            return Connection.reconstitute(
                    new ConnectionId(UUID.randomUUID()), organizationId, owner,
                    LarkCredentialAccessManager.CONNECTOR_KEY, externalReference,
                    new CredentialId(UUID.randomUUID()), ConnectionStatus.ACTIVE,
                    Optional.empty(), Optional.empty(), 0, AuditMetadata.createdBy(actor, now));
        }

        private ConnectionGrant grant(
                Connection connection, ProviderOwner owner, ProviderAccessScope scope) {
            return ConnectionGrant.reconstitute(
                    new ConnectionGrantId(UUID.randomUUID()), organizationId, connection.id(),
                    owner, owner, scope, now, Optional.empty(), ConnectionGrantStatus.ACTIVE,
                    Optional.empty(), 0, AuditMetadata.createdBy(actor, now));
        }

        private LarkApiCallContext context(
                Connection connection, ConnectionGrant grant, String tenantKey) {
            return new LarkApiCallContext(
                    new LarkConnectionAuthorization(
                            organizationId, teamId,
                            new io.crewscope.domain.provider.ProviderBindingId(UUID.randomUUID()),
                            0, connection.id(), connection.version(), grant.id(), grant.version(),
                            new LarkTenantKey(tenantKey),
                            LarkCollaborationCapabilities.COMPLETE),
                    actor);
        }

        private void rotateSecretA() {
            credentials.secretVersionA++;
        }

        @Override
        public void close() {
            clientA.close();
        }
    }

    private static final class MutableConnections implements ConnectionRepository {
        private final Connection connectionA;
        private final Connection connectionB;
        private boolean availableA = true;

        private MutableConnections(Connection connectionA, Connection connectionB) {
            this.connectionA = connectionA;
            this.connectionB = connectionB;
        }

        @Override
        public Optional<Connection> findById(OrganizationId organizationId, ConnectionId id) {
            if (id.equals(connectionA.id())) {
                return availableA ? Optional.of(connectionA) : Optional.empty();
            }
            return id.equals(connectionB.id()) ? Optional.of(connectionB) : Optional.empty();
        }

        @Override public Connection create(Connection value) { throw unsupported(); }
        @Override public Connection update(Connection value) { throw unsupported(); }
        @Override public List<Connection> findByOwner(ProviderOwner owner) { return List.of(); }
    }

    private static final class MutableGrants implements ConnectionGrantRepository {
        private final ConnectionGrant grantA;
        private final ConnectionGrant grantB;
        private boolean availableA = true;

        private MutableGrants(ConnectionGrant grantA, ConnectionGrant grantB) {
            this.grantA = grantA;
            this.grantB = grantB;
        }

        @Override
        public Optional<ConnectionGrant> findById(
                OrganizationId organizationId, ConnectionGrantId id) {
            if (id.equals(grantA.id())) {
                return availableA ? Optional.of(grantA) : Optional.empty();
            }
            return id.equals(grantB.id()) ? Optional.of(grantB) : Optional.empty();
        }

        @Override public ConnectionGrant create(ConnectionGrant value) { throw unsupported(); }
        @Override public ConnectionGrant update(ConnectionGrant value) { throw unsupported(); }
        @Override public List<ConnectionGrant> findByConnectionAndGrantee(
                ConnectionId connectionId, ProviderOwner grantee) { return List.of(); }
    }

    private static final class MutableCredentials implements CredentialStore {
        private final OrganizationId organizationId;
        private final TeamId teamId;
        private final PrincipalId actor;
        private final UtcTimestamp now;
        private final Connection connectionA;
        private final Connection connectionB;
        private final List<CredentialSecret> resolvedSecrets = new ArrayList<>();
        private boolean availableA = true;
        private long secretVersionA;

        private MutableCredentials(
                OrganizationId organizationId,
                TeamId teamId,
                PrincipalId actor,
                UtcTimestamp now,
                Connection connectionA,
                Connection connectionB) {
            this.organizationId = organizationId;
            this.teamId = teamId;
            this.actor = actor;
            this.now = now;
            this.connectionA = connectionA;
            this.connectionB = connectionB;
        }

        @Override
        public Optional<CredentialDescriptor> describe(
                CredentialReference reference, CredentialAccessContext accessContext) {
            Connection connection = connection(reference);
            if (connection == null || (connection == connectionA && !availableA)) {
                return Optional.empty();
            }
            return Optional.of(descriptor(connection));
        }

        @Override
        public Optional<ResolvedCredential> resolve(
                CredentialReference reference, CredentialAccessContext accessContext) {
            Connection connection = connection(reference);
            if (connection == null || (connection == connectionA && !availableA)) {
                return Optional.empty();
            }
            String app = connection == connectionA ? APP_A : APP_B;
            CredentialSecret secret = CredentialSecret.utf8(
                    "{\"app_id\":\"" + app + "\",\"app_secret\":\""
                            + SECRET_MARKER + "\"}");
            synchronized (resolvedSecrets) {
                resolvedSecrets.add(secret);
            }
            return Optional.of(new ResolvedCredential(descriptor(connection), secret));
        }

        private Connection connection(CredentialReference reference) {
            if (reference.credentialId().equals(connectionA.credentialId())) {
                return connectionA;
            }
            return reference.credentialId().equals(connectionB.credentialId())
                    ? connectionB : null;
        }

        private CredentialDescriptor descriptor(Connection connection) {
            long version = connection == connectionA ? secretVersionA : 0;
            return new CredentialDescriptor(
                    connection.credentialId(), CredentialSubject.team(organizationId, teamId),
                    "lark-m6-i04", LarkCredentialAccessManager.CONNECTOR_KEY,
                    Optional.of(connection.id().value()),
                    LarkCredentialAccessManager.CREDENTIAL_TYPE, Map.of(),
                    CredentialStatus.ACTIVE, Optional.empty(), Optional.empty(), Optional.empty(),
                    "test-key", "AES-256-GCM", "1", actor, actor, now, now, version, version);
        }

        private boolean allResolvedSecretsClosed() {
            synchronized (resolvedSecrets) {
                return !resolvedSecrets.isEmpty()
                        && resolvedSecrets.stream().allMatch(CredentialSecret::isClosed);
            }
        }

        @Override public CredentialDescriptor create(
                CredentialCreateRequest request, CredentialSecret secret) { throw unsupported(); }
        @Override public CredentialDescriptor rotate(
                CredentialReference reference, long version, CredentialMutationContext context,
                CredentialSecret secret) { throw unsupported(); }
        @Override public CredentialDescriptor revoke(
                CredentialReference reference, long version, CredentialMutationContext context,
                CredentialRevocationReason reason) { throw unsupported(); }
    }

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("test read fixture");
    }

    private static final class LarkStub implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor = Executors.newCachedThreadPool();
        private final Map<String, AtomicInteger> tokenRequests = new ConcurrentHashMap<>();
        private final AtomicInteger totalRequests = new AtomicInteger();
        private final AtomicInteger rejectNextTenantA = new AtomicInteger();
        private final AtomicInteger nextStatus = new AtomicInteger();
        private final AtomicInteger delayMillis = new AtomicInteger();
        private final List<String> businessPaths = new java.util.concurrent.CopyOnWriteArrayList<>();
        private volatile boolean invalidTenantResponse;
        private volatile boolean invalidJsonResponse;
        private volatile boolean oversizedTenantResponse;
        private volatile CountDownLatch nextBusinessRequest = new CountDownLatch(0);

        private LarkStub(HttpServer server) {
            this.server = server;
            server.setExecutor(executor);
            server.createContext("/", this::handle);
            server.start();
        }

        private static LarkStub start() throws IOException {
            return new LarkStub(HttpServer.create(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0));
        }

        private URI baseUri() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        private int tokenRequests(String app) {
            return tokenRequests.getOrDefault(app, new AtomicInteger()).get();
        }

        private CountDownLatch armNextBusinessRequest() {
            CountDownLatch latch = new CountDownLatch(1);
            nextBusinessRequest = latch;
            return latch;
        }

        private void handle(HttpExchange exchange) throws IOException {
            totalRequests.incrementAndGet();
            try (exchange) {
                String path = exchange.getRequestURI().toString();
                if (!path.equals("/open-apis/auth/v3/tenant_access_token/internal")) {
                    nextBusinessRequest.countDown();
                }
                int delay = delayMillis.get();
                if (delay > 0) {
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
                if (path.equals("/open-apis/auth/v3/tenant_access_token/internal")) {
                    token(exchange);
                    return;
                }
                businessPaths.add(path);
                int status = nextStatus.getAndSet(0);
                if (status > 0) {
                    if (status == 429) {
                        exchange.getResponseHeaders().add("Retry-After", "7");
                    }
                    respond(exchange, status, "{\"msg\":\"provider-internal-body "
                            + SECRET_MARKER + " token-m6i04\"}");
                    return;
                }
                String authorization = exchange.getRequestHeaders()
                        .getFirst("Authorization");
                if (authorization == null || !authorization.startsWith("Bearer token-m6i04-")) {
                    respond(exchange, 401, "{}");
                    return;
                }
                if (authorization.contains(APP_A)
                        && rejectNextTenantA.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
                    respond(exchange, 401, "{}");
                    return;
                }
                String tenant = authorization.contains(APP_A) ? "tenant-a" : "tenant-b";
                if (path.equals("/open-apis/tenant/v2/tenant/query")) {
                    if (invalidJsonResponse) {
                        respond(exchange, 200, "{not-json");
                    } else if (oversizedTenantResponse) {
                        respond(exchange, 200,
                                "{\"code\":0,\"padding\":\"" + "x".repeat(2_000)
                                        + "\",\"data\":{\"tenant\":{\"tenant_key\":\""
                                        + tenant + "\"}}}");
                    } else {
                        respond(exchange, 200, invalidTenantResponse
                                ? "{\"code\":0,\"data\":{}}"
                                : "{\"code\":0,\"data\":{\"tenant\":{\"tenant_key\":\""
                                        + tenant + "\"}}}");
                    }
                } else if (path.startsWith("/open-apis/contact/v3/users/")) {
                    respond(exchange, 200,
                            "{\"code\":0,\"data\":{\"user\":{\"open_id\":\"ou_member_a\","
                                    + "\"union_id\":\"on_member_a\"}}}");
                } else if (path.equals(
                        "/open-apis/im/v1/messages?receive_id_type=open_id")) {
                    respond(exchange, 200,
                            "{\"code\":0,\"data\":{\"message_id\":\"om_m6i04_message\"}}");
                } else if (path.equals("/open-apis/im/v1/messages/om_m6i04_message")) {
                    respond(exchange, 200,
                            "{\"code\":0,\"data\":{\"items\":[{\"message_id\":"
                                    + "\"om_m6i04_message\"}]}}");
                } else {
                    respond(exchange, 404, "{}");
                }
            }
        }

        private void token(HttpExchange exchange) throws IOException {
            String body = new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8);
            String app = body.contains(APP_A) ? APP_A : APP_B;
            int count = tokenRequests.computeIfAbsent(app, ignored -> new AtomicInteger())
                    .incrementAndGet();
            respond(exchange, 200,
                    "{\"code\":0,\"tenant_access_token\":\"token-m6i04-" + app + '-'
                            + count + "\",\"expire\":7200}");
        }

        private static void respond(HttpExchange exchange, int status, String body)
                throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
