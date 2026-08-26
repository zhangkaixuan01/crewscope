package io.crewscope.integration.provider.collaboration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.crewscope.application.collaboration.LarkExternalTenantRepository;
import io.crewscope.application.collaboration.LarkMemberMappingPage;
import io.crewscope.application.collaboration.LarkMemberMappingPageRequest;
import io.crewscope.application.collaboration.LarkMemberMappingRepository;
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
import io.crewscope.application.notification.FixedNotificationTemplateRenderer;
import io.crewscope.application.notification.NotificationProviderRequest;
import io.crewscope.application.notification.NotificationQueryResult;
import io.crewscope.application.notification.NotificationSendResult;
import io.crewscope.application.provider.ConnectionGrantRepository;
import io.crewscope.application.provider.ConnectionRepository;
import io.crewscope.application.team.TeamMemberRepository;
import io.crewscope.domain.action.ActionDigest;
import io.crewscope.domain.action.PlannedActionId;
import io.crewscope.domain.collaboration.LarkCollaborationCapabilities;
import io.crewscope.domain.collaboration.LarkConnectionAuthorization;
import io.crewscope.domain.collaboration.LarkExternalMemberKey;
import io.crewscope.domain.collaboration.LarkExternalTenant;
import io.crewscope.domain.collaboration.LarkExternalTenantId;
import io.crewscope.domain.collaboration.LarkInternalMemberKey;
import io.crewscope.domain.collaboration.LarkMemberMapping;
import io.crewscope.domain.collaboration.LarkMemberMappingId;
import io.crewscope.domain.collaboration.LarkMemberMappingStatus;
import io.crewscope.domain.collaboration.LarkOpenId;
import io.crewscope.domain.collaboration.LarkProviderVersion;
import io.crewscope.domain.collaboration.LarkTenantKey;
import io.crewscope.domain.collaboration.LarkUnionId;
import io.crewscope.domain.collaboration.LarkVerificationSource;
import io.crewscope.domain.inbox.InboxItemType;
import io.crewscope.domain.inbox.InboxSourceKey;
import io.crewscope.domain.inbox.InboxSourceRevision;
import io.crewscope.domain.inbox.InboxSourceType;
import io.crewscope.domain.notification.NotificationAuthorizationFacts;
import io.crewscope.domain.notification.NotificationAuthorizationSnapshot;
import io.crewscope.domain.notification.NotificationIntent;
import io.crewscope.domain.notification.NotificationIntentId;
import io.crewscope.domain.notification.NotificationPreference;
import io.crewscope.domain.notification.NotificationRecipientMappingId;
import io.crewscope.domain.notification.NotificationTemplate;
import io.crewscope.domain.notification.NotificationTemplateId;
import io.crewscope.domain.notification.NotificationTemplateRef;
import io.crewscope.domain.notification.NotificationTemplateStatus;
import io.crewscope.domain.notification.NotificationTemplateVersion;
import io.crewscope.domain.notification.NotificationVariableSpec;
import io.crewscope.domain.notification.TeamNotificationPolicyId;
import io.crewscope.domain.projection.ProjectionGeneration;
import io.crewscope.domain.provider.Connection;
import io.crewscope.domain.provider.ConnectionGrant;
import io.crewscope.domain.provider.ConnectionGrantId;
import io.crewscope.domain.provider.ConnectionGrantStatus;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.provider.ConnectionStatus;
import io.crewscope.domain.provider.ProviderAccessScope;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.provider.ProviderOwner;
import io.crewscope.domain.provider.ProviderOwnerType;
import io.crewscope.domain.provider.ProviderResourceScope;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.audit.LifecycleMetadata;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.id.CredentialId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.team.TeamJoinMethod;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.team.TeamMemberStatus;
import io.crewscope.domain.team.TeamScope;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** M6-I06 end-to-end fixed-template delivery tests against a loopback Lark substitute. */
class LarkNotificationProviderM6I06IntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-26T10:00:00Z");

    @Test
    void rendersDoubleEscapedJsonAndDeduplicatesRepeatedRequests() throws Exception {
        try (LarkStub stub = LarkStub.start(); Fixture fixture = new Fixture(stub)) {
            NotificationSendResult first = fixture.provider.send(
                    fixture.request, fixture.credential);
            NotificationSendResult duplicate = fixture.provider.send(
                    fixture.request, fixture.credential);

            assertEquals(NotificationSendResult.Kind.ACCEPTED, first.kind());
            assertEquals(first.providerReference(), duplicate.providerReference());
            assertEquals(first.providerMessageId(), duplicate.providerMessageId());
            assertEquals(1, stub.createdMessages.get());
            assertEquals(2, stub.messageWrites.get());
            assertEquals(fixture.expectedText, stub.lastDecodedText);
            assertTrue(stub.lastRawBody.contains("\\\\\\\"quoted\\\\\\\""));
            assertEquals(32, first.providerReference().orElseThrow().length());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new NotificationProviderRequest(
                            fixture.request.organizationId(),
                            fixture.request.teamId(),
                            fixture.request.recipientMemberId(),
                            fixture.request.actionId(),
                            fixture.request.actionDigest(),
                            fixture.request.template(),
                            fixture.request.variableHash(),
                            fixture.request.recipientMappingId(),
                            fixture.request.connectionId(),
                            fixture.request.deduplicationKey(),
                            fixture.request.authorization(),
                            fixture.request.variables(),
                            UUID.randomUUID(),
                            fixture.request.attempt()));
        }
    }

    @Test
    void responseLossRecoversTheOriginalMessageWithTheSameProviderUuid() throws Exception {
        try (LarkStub stub = LarkStub.start(); Fixture fixture = new Fixture(stub)) {
            stub.dropFirstWriteResponse.set(true);

            NotificationSendResult uncertain = fixture.provider.send(
                    fixture.request, fixture.credential);
            NotificationQueryResult recovered = fixture.provider.query(
                    fixture.request, fixture.credential);

            assertEquals(NotificationSendResult.Kind.UNKNOWN, uncertain.kind());
            assertEquals(NotificationQueryResult.Kind.FOUND, recovered.kind());
            assertEquals(1, stub.createdMessages.get());
            assertEquals(2, stub.messageWrites.get());
            assertEquals(List.of(stub.observedUuids.get(0), stub.observedUuids.get(0)),
                    stub.observedUuids);
            assertEquals("om_m6i06_1", recovered.providerMessageId().orElseThrow());
        }
    }

    @Test
    void staleMappingTemplateConnectionAndGrantPerformNoHttpWrite() throws Exception {
        try (LarkStub stub = LarkStub.start(); Fixture fixture = new Fixture(stub)) {
            fixture.mappings.available = false;
            assertEquals(NotificationSendResult.Kind.FAILED_FINAL,
                    fixture.provider.send(fixture.request, fixture.credential).kind());
            assertEquals(0, stub.messageWrites.get());

            fixture.mappings.available = true;
            fixture.templateAvailable = false;
            assertEquals(NotificationSendResult.Kind.FAILED_FINAL,
                    fixture.provider.send(fixture.request, fixture.credential).kind());
            assertEquals(0, stub.messageWrites.get());

            fixture.templateAvailable = true;
            fixture.connections.available = false;
            assertEquals(NotificationSendResult.Kind.FAILED_FINAL,
                    fixture.provider.send(fixture.request, fixture.credential).kind());
            assertEquals(0, stub.messageWrites.get());

            fixture.connections.available = true;
            fixture.grants.available = false;
            assertEquals(NotificationSendResult.Kind.FAILED_FINAL,
                    fixture.provider.send(fixture.request, fixture.credential).kind());
            assertEquals(0, stub.messageWrites.get());
        }
    }

    @Test
    void mergesOnlyIdenticalReceiptIdentityAndKeepsTheNewestObservation() {
        LarkNotificationUuid uuid = LarkNotificationUuid.from(UUID.randomUUID());
        LarkMessageId message = new LarkMessageId("om_m6i06_receipt");
        LarkMessageReceiptProjection current = new LarkMessageReceiptProjection(
                uuid, message, UtcTimestamp.parse("2026-08-26T10:00:02Z"));
        LarkMessageReceiptProjection older = new LarkMessageReceiptProjection(
                uuid, message, UtcTimestamp.parse("2026-08-26T10:00:01Z"));

        assertEquals(current, current.merge(older));
        assertEquals(
                UtcTimestamp.parse("2026-08-26T10:00:03Z"),
                current.merge(new LarkMessageReceiptProjection(
                        uuid,
                        message,
                        UtcTimestamp.parse("2026-08-26T10:00:03Z")))
                        .externalObservedAt());
        assertEquals(
                LarkProviderErrorCode.INVALID_RESPONSE,
                assertThrows(
                        LarkProviderException.class,
                        () -> current.merge(new LarkMessageReceiptProjection(
                                uuid,
                                new LarkMessageId("om_m6i06_conflict"),
                                UtcTimestamp.parse("2026-08-26T10:00:04Z"))))
                        .code());
    }

    private static final class Fixture implements AutoCloseable {
        private final OrganizationId organizationId = OrganizationId.generate();
        private final TeamId teamId = TeamId.generate();
        private final TeamMemberId memberId = TeamMemberId.generate();
        private final PrincipalId actor = PrincipalId.generate();
        private final ProviderBindingId bindingId = ProviderBindingId.generate();
        private final LarkMemberMappingId mappingId = LarkMemberMappingId.generate();
        private final UtcTimestamp now = UtcTimestamp.from(NOW);
        private final Connection connection;
        private final ConnectionGrant grant;
        private final LarkConnectionAuthorization authorization;
        private final MutableConnections connections;
        private final MutableGrants grants;
        private final MutableMappings mappings;
        private final LarkNotificationCredentialHandle credential;
        private final LarkNotificationProviderAdapter provider;
        private final NotificationProviderRequest request;
        private final String expectedText;
        private boolean templateAvailable = true;
        private final LarkOpenApiClient client;

        private Fixture(LarkStub stub) {
            ProviderOwner owner = new ProviderOwner(
                    organizationId,
                    ProviderOwnerType.TEAM,
                    teamId.value(),
                    Optional.of(teamId),
                    Optional.empty());
            connection = Connection.reconstitute(
                    ConnectionId.generate(),
                    organizationId,
                    owner,
                    LarkCredentialAccessManager.CONNECTOR_KEY,
                    "tenant-m6i06",
                    CredentialId.generate(),
                    ConnectionStatus.ACTIVE,
                    Optional.empty(),
                    Optional.empty(),
                    0,
                    AuditMetadata.createdBy(actor, now));
            ProviderAccessScope access = new ProviderAccessScope(
                    LarkCollaborationCapabilities.COMPLETE,
                    ProviderResourceScope.allResources());
            grant = ConnectionGrant.reconstitute(
                    ConnectionGrantId.generate(),
                    organizationId,
                    connection.id(),
                    owner,
                    owner,
                    access,
                    now,
                    Optional.empty(),
                    ConnectionGrantStatus.ACTIVE,
                    Optional.empty(),
                    0,
                    AuditMetadata.createdBy(actor, now));
            authorization = new LarkConnectionAuthorization(
                    organizationId,
                    teamId,
                    bindingId,
                    0,
                    connection.id(),
                    connection.version(),
                    grant.id(),
                    grant.version(),
                    new LarkTenantKey("tenant-m6i06"),
                    LarkCollaborationCapabilities.COMPLETE);
            TimeProvider time = TimeProvider.from(Clock.fixed(NOW, ZoneOffset.UTC));
            connections = new MutableConnections(connection);
            grants = new MutableGrants(grant);
            LarkCredentialAccessManager accessManager = new LarkCredentialAccessManager(
                    connections,
                    grants,
                    new TestCredentials(connection, teamId, actor, now),
                    time,
                    Duration.ofMinutes(5));
            LarkTenantTokenCache cache = new LarkTenantTokenCache(
                    16, Duration.ofSeconds(60));
            client = new LarkOpenApiClient(
                    HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
                    new ObjectMapper(),
                    stub.baseUri(),
                    true,
                    Duration.ofSeconds(2),
                    1_048_576,
                    time,
                    accessManager,
                    cache);
            credential = new LarkNotificationCredentialHandle(
                    new LarkApiCallContext(authorization, actor),
                    accessManager,
                    time,
                    UtcTimestamp.from(NOW.plusSeconds(300)));

            TeamMember member = TeamMember.reconstitute(
                    memberId,
                    new TeamScope(organizationId, teamId),
                    actor,
                    TeamMemberStatus.ACTIVE,
                    TeamJoinMethod.BOOTSTRAP,
                    Optional.empty(),
                    Optional.of(now),
                    Optional.empty(),
                    0,
                    LifecycleMetadata.createdAt(now));
            LarkExternalTenant tenant = LarkExternalTenant.verify(
                    authorization,
                    authorization.expectedTenantKey(),
                    new LarkProviderVersion("m6-i06-loopback-v1"),
                    now);
            LarkMemberMapping mapping = LarkMemberMapping.reconstitute(
                    mappingId,
                    organizationId,
                    teamId,
                    memberId,
                    bindingId,
                    0,
                    connection.id(),
                    connection.version(),
                    grant.id(),
                    grant.version(),
                    tenant.id(),
                    tenant.version(),
                    tenant.tenantKey(),
                    new LarkOpenId("ou_m6i06_member"),
                    new LarkUnionId("on_m6i06_member"),
                    new LarkProviderVersion("m6-i06-loopback-v1"),
                    LarkVerificationSource.LARK_OPEN_API_EXACT_OPEN_ID,
                    now,
                    actor,
                    LarkMemberMappingStatus.ACTIVE,
                    Optional.empty(),
                    0,
                    AuditMetadata.createdBy(actor, now));
            mappings = new MutableMappings(mapping);
            MutableTenants tenants = new MutableTenants(tenant);
            MutableMembers members = new MutableMembers(member);

            NotificationTemplate template = new NotificationTemplate(
                    new NotificationTemplateRef(
                            NotificationTemplateId.generate(),
                            new NotificationTemplateVersion(1)),
                    "review-required",
                    Map.of(
                            "workItemTitle",
                            NotificationVariableSpec.text("workItemTitle", 500),
                            "reviewUrl",
                            NotificationVariableSpec.text("reviewUrl", 500)),
                    NotificationTemplateStatus.PUBLISHED);
            FixedNotificationTemplateRenderer renderer =
                    new FixedNotificationTemplateRenderer(ref -> {
                        if (!templateAvailable || !template.ref().equals(ref)) {
                            throw new IllegalStateException("Template drifted");
                        }
                        return template;
                    });
            provider = new LarkNotificationProviderAdapter(
                    client, renderer, mappings, tenants, members);

            Map<String, String> values = Map.of(
                    "workItemTitle", "Review \"quoted\" \\ path",
                    "reviewUrl", "https://crewscope.example/reviews/42?mode=full&owner=me");
            NotificationIntent intent = new NotificationIntent(
                    new NotificationIntentId(UUID.randomUUID()),
                    organizationId,
                    teamId,
                    memberId,
                    new InboxSourceKey(
                            organizationId,
                            memberId,
                            InboxItemType.REVIEW,
                            InboxSourceType.REVIEW_REQUEST,
                            UUID.randomUUID(),
                            InboxSourceRevision.INITIAL),
                    ProjectionGeneration.FIRST,
                    SchemaVersion.V1,
                    template.ref(),
                    template.validateVariables(values),
                    now);
            NotificationAuthorizationFacts facts = new NotificationAuthorizationFacts(
                    intent,
                    new NotificationRecipientMappingId(mappingId.value()),
                    mapping.version(),
                    bindingId,
                    0,
                    connection.id(),
                    connection.version(),
                    grant.id(),
                    grant.version(),
                    TeamNotificationPolicyId.generate(),
                    0,
                    new NotificationPreference(
                            memberId,
                            true,
                            Set.of(InboxItemType.REVIEW),
                            Optional.empty(),
                            0));
            NotificationAuthorizationSnapshot snapshot =
                    NotificationAuthorizationSnapshot.captureAutomatic(facts);
            PlannedActionId actionId = PlannedActionId.generate();
            ActionDigest actionDigest = new ActionDigest(
                    TaskFactHash.sha256("m6-i06-action"));
            request = new NotificationProviderRequest(
                    organizationId,
                    teamId,
                    memberId,
                    actionId,
                    actionDigest,
                    template.ref(),
                    intent.variables().hash(),
                    facts.recipientMappingId(),
                    connection.id(),
                    snapshot.deduplicationKey(),
                    snapshot,
                    intent.variables(),
                    NotificationProviderRequest.stableIdempotencyKey(
                            organizationId,
                            connection.id(),
                            actionId,
                            actionDigest,
                            snapshot.deduplicationKey()),
                    1);
            expectedText = renderer.render(template.ref(), intent.variables()).text();
        }

        @Override
        public void close() {
            credential.close();
            client.close();
        }
    }

    private static final class MutableConnections implements ConnectionRepository {
        private final Connection connection;
        private boolean available = true;

        private MutableConnections(Connection connection) {
            this.connection = connection;
        }

        @Override
        public Optional<Connection> findById(OrganizationId organizationId, ConnectionId id) {
            return available && connection.organizationId().equals(organizationId)
                            && connection.id().equals(id)
                    ? Optional.of(connection)
                    : Optional.empty();
        }

        @Override public Connection create(Connection value) { throw unsupported(); }
        @Override public Connection update(Connection value) { throw unsupported(); }
        @Override public List<Connection> findByOwner(ProviderOwner owner) { return List.of(); }
    }

    private static final class MutableGrants implements ConnectionGrantRepository {
        private final ConnectionGrant grant;
        private boolean available = true;

        private MutableGrants(ConnectionGrant grant) {
            this.grant = grant;
        }

        @Override
        public Optional<ConnectionGrant> findById(
                OrganizationId organizationId, ConnectionGrantId id) {
            return available && grant.organizationId().equals(organizationId)
                            && grant.id().equals(id)
                    ? Optional.of(grant)
                    : Optional.empty();
        }

        @Override public ConnectionGrant create(ConnectionGrant value) { throw unsupported(); }
        @Override public ConnectionGrant update(ConnectionGrant value) { throw unsupported(); }
        @Override public List<ConnectionGrant> findByConnectionAndGrantee(
                ConnectionId connectionId, ProviderOwner grantee) { return List.of(); }
    }

    private static final class MutableMappings implements LarkMemberMappingRepository {
        private final LarkMemberMapping mapping;
        private boolean available = true;

        private MutableMappings(LarkMemberMapping mapping) {
            this.mapping = mapping;
        }

        @Override
        public Optional<LarkMemberMapping> findById(
                OrganizationId organizationId, LarkMemberMappingId id) {
            return available && mapping.organizationId().equals(organizationId)
                            && mapping.id().equals(id)
                    ? Optional.of(mapping)
                    : Optional.empty();
        }

        @Override public Optional<LarkMemberMapping> findActiveByInternalKey(
                LarkInternalMemberKey key) { return Optional.empty(); }
        @Override public Optional<LarkMemberMapping> findActiveByExternalKey(
                LarkExternalMemberKey key) { return Optional.empty(); }
        @Override public LarkMemberMappingPage findPage(LarkMemberMappingPageRequest request) {
            throw unsupported();
        }
        @Override public LarkMemberMapping createActive(LarkMemberMapping value) {
            throw unsupported();
        }
        @Override public LarkMemberMapping replaceActive(
                LarkMemberMapping terminated, LarkMemberMapping replacement) {
            throw unsupported();
        }
        @Override public LarkMemberMapping update(LarkMemberMapping value) {
            throw unsupported();
        }
    }

    private static final class MutableTenants implements LarkExternalTenantRepository {
        private final LarkExternalTenant tenant;

        private MutableTenants(LarkExternalTenant tenant) {
            this.tenant = tenant;
        }

        @Override public Optional<LarkExternalTenant> findById(
                OrganizationId organizationId, LarkExternalTenantId id) {
            return Optional.empty();
        }
        @Override public Optional<LarkExternalTenant> findByConnection(
                OrganizationId organizationId, ConnectionId connectionId) {
            return tenant.organizationId().equals(organizationId)
                            && tenant.connectionId().equals(connectionId)
                    ? Optional.of(tenant)
                    : Optional.empty();
        }
        @Override public LarkExternalTenant create(LarkExternalTenant value) {
            throw unsupported();
        }
        @Override public LarkExternalTenant update(LarkExternalTenant value) {
            throw unsupported();
        }
    }

    private static final class MutableMembers implements TeamMemberRepository {
        private final TeamMember member;

        private MutableMembers(TeamMember member) {
            this.member = member;
        }

        @Override public TeamMember create(TeamMember value) { throw unsupported(); }
        @Override public Optional<TeamMember> findById(
                OrganizationId organizationId, TeamMemberId id) {
            return member.scope().organizationId().equals(organizationId)
                            && member.id().equals(id)
                    ? Optional.of(member)
                    : Optional.empty();
        }
    }

    private static final class TestCredentials implements CredentialStore {
        private final Connection connection;
        private final CredentialDescriptor descriptor;

        private TestCredentials(
                Connection connection,
                TeamId teamId,
                PrincipalId actor,
                UtcTimestamp now) {
            this.connection = connection;
            descriptor = new CredentialDescriptor(
                    connection.credentialId(),
                    CredentialSubject.team(connection.organizationId(), teamId),
                    "lark-m6-i06",
                    LarkCredentialAccessManager.CONNECTOR_KEY,
                    Optional.of(connection.id().value()),
                    LarkCredentialAccessManager.CREDENTIAL_TYPE,
                    Map.of(),
                    CredentialStatus.ACTIVE,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    "test-key",
                    "AES-256-GCM",
                    "1",
                    actor,
                    actor,
                    now,
                    now,
                    0,
                    0);
        }

        @Override
        public Optional<CredentialDescriptor> describe(
                CredentialReference reference, CredentialAccessContext context) {
            return matches(reference) ? Optional.of(descriptor) : Optional.empty();
        }

        @Override
        public Optional<ResolvedCredential> resolve(
                CredentialReference reference, CredentialAccessContext context) {
            return matches(reference)
                    ? Optional.of(new ResolvedCredential(
                            descriptor,
                            CredentialSecret.utf8(
                                    "{\"app_id\":\"cli_m6i06\","
                                            + "\"app_secret\":\"secret-m6i06\"}")))
                    : Optional.empty();
        }

        private boolean matches(CredentialReference reference) {
            return reference.organizationId().equals(connection.organizationId())
                    && reference.credentialId().equals(connection.credentialId());
        }

        @Override public CredentialDescriptor create(
                CredentialCreateRequest request, CredentialSecret secret) { throw unsupported(); }
        @Override public CredentialDescriptor rotate(
                CredentialReference reference,
                long version,
                CredentialMutationContext context,
                CredentialSecret secret) { throw unsupported(); }
        @Override public CredentialDescriptor revoke(
                CredentialReference reference,
                long version,
                CredentialMutationContext context,
                CredentialRevocationReason reason) { throw unsupported(); }
    }

    private static final class LarkStub implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor = Executors.newCachedThreadPool();
        private final ObjectMapper mapper = new ObjectMapper();
        private final Map<String, String> messagesByUuid = new ConcurrentHashMap<>();
        private final AtomicInteger messageWrites = new AtomicInteger();
        private final AtomicInteger createdMessages = new AtomicInteger();
        private final AtomicBoolean dropFirstWriteResponse = new AtomicBoolean();
        private final List<String> observedUuids =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        private volatile String lastRawBody = "";
        private volatile String lastDecodedText = "";

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

        private void handle(HttpExchange exchange) throws IOException {
            try (exchange) {
                String path = exchange.getRequestURI().toString();
                if (path.equals("/open-apis/auth/v3/tenant_access_token/internal")) {
                    exchange.getRequestBody().readAllBytes();
                    respond(exchange, 200,
                            "{\"code\":0,\"tenant_access_token\":\"token-m6i06\","
                                    + "\"expire\":7200}");
                    return;
                }
                if (path.equals("/open-apis/im/v1/messages?receive_id_type=open_id")) {
                    lastRawBody = new String(
                            exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    JsonNode body = mapper.readTree(lastRawBody);
                    String uuid = body.path("uuid").stringValue();
                    observedUuids.add(uuid);
                    lastDecodedText = mapper.readTree(body.path("content").stringValue())
                            .path("text").stringValue();
                    messageWrites.incrementAndGet();
                    String messageId = messagesByUuid.computeIfAbsent(uuid, ignored -> {
                        int sequence = createdMessages.incrementAndGet();
                        return "om_m6i06_" + sequence;
                    });
                    if (dropFirstWriteResponse.compareAndSet(true, false)) {
                        // Simulate acceptance followed by transport loss before any response bytes.
                        return;
                    }
                    respond(exchange, 200,
                            "{\"code\":0,\"data\":{\"message_id\":\""
                                    + messageId + "\"}}");
                    return;
                }
                if (path.startsWith("/open-apis/im/v1/messages/")) {
                    String messageId = path.substring(path.lastIndexOf('/') + 1);
                    respond(exchange, 200,
                            "{\"code\":0,\"data\":{\"items\":[{\"message_id\":\""
                                    + messageId + "\"}]}}");
                    return;
                }
                respond(exchange, 404, "{\"code\":404}");
            }
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

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("M6-I06 read-only test fixture");
    }
}
