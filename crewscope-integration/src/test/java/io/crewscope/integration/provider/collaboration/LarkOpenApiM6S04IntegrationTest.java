package io.crewscope.integration.provider.collaboration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * M6-S04 Loopback proof for Lark tenant identity, exact member mapping and notification delivery.
 *
 * <p>Every connector type remains nested and test-only. The spike freezes the HTTP and recovery
 * contract before M6-D04 and M6-I04/I06 add production domain objects and adapters.
 */
@Tag("integration")
class LarkOpenApiM6S04IntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-08-25T09:00:00Z");
    private static final Duration REQUEST_TIMEOUT = Duration.ofMillis(150);
    private static final String APP_A = "cli_a_m6s04";
    private static final String APP_B = "cli_b_m6s04";
    private static final String SECRET_A = "lark_app_secret_a_m6s04";
    private static final String SECRET_B = "lark_app_secret_b_m6s04";
    private static final String TOKEN_MARKER = "tenant_access_token_m6s04";
    private static final String RAW_PROVIDER_MARKER = "lark-internal.example.invalid";

    @Test
    void cachesTenantTokensByClosedAuthorizationCoordinatesAndRefreshesAfter401()
            throws Exception {
        try (LarkStub stub = LarkStub.start()) {
            CredentialResolver credentials = credentials();
            LarkClient client = client(stub, credentials, delay -> {});
            TenantConnection tenantA = tenantA();
            TenantConnection tenantB = tenantB();

            assertEquals("tenant-a", client.queryTenant(tenantA).tenantKey());
            assertEquals("tenant-a", client.queryTenant(tenantA).tenantKey());
            assertEquals("tenant-b", client.queryTenant(tenantB).tenantKey());
            assertEquals(1, stub.tokenRequests(APP_A));
            assertEquals(1, stub.tokenRequests(APP_B));

            stub.rejectNextAuthorizedRequest("tenant-a");
            assertEquals("ou_a_100", client.resolveMember(
                    tenantA, ExternalLookupType.OPEN_ID, "ou_a_100").openId());

            assertEquals(2, stub.tokenRequests(APP_A));
            assertEquals(1, stub.tokenRequests(APP_B));
            assertEquals(3, credentials.resolveCount());
            assertEquals(3, credentials.closedHandleCount());
            assertFalse(client.safeSummary().contains(SECRET_A));
            assertFalse(client.safeSummary().contains(SECRET_B));
            assertFalse(client.safeSummary().contains(TOKEN_MARKER));
            assertFalse(client.safeSummary().contains(stub.baseUri().toString()));
        }
    }

    @Test
    void confirmsOnlyExactTenantAndOpenIdMappingsAndRejectsFuzzyOrConflictingIdentity()
            throws Exception {
        try (LarkStub stub = LarkStub.start()) {
            LarkClient client = client(stub, credentials(), delay -> {});
            TenantConnection connection = tenantA();
            LarkMemberProof proof = client.resolveMember(
                    connection, ExternalLookupType.OPEN_ID, "ou_a_100");
            MemberMappingRegistry mappings = new MemberMappingRegistry();

            LarkMemberMapping mapping = mappings.confirm(
                    true, "member-100", connection, proof);

            assertEquals("organization-1", mapping.organizationId());
            assertEquals("team-1", mapping.teamId());
            assertEquals("connection-a", mapping.connectionId());
            assertEquals(3, mapping.connectionVersion());
            assertEquals("tenant-a", mapping.tenantKey());
            assertEquals("ou_a_100", mapping.openId());
            assertEquals("v3", mapping.providerVersion());
            assertEquals(1, mapping.mappingVersion());
            int exactQueries = stub.memberQueries();
            assertThrows(ProtocolViolation.class, () -> client.resolveMember(
                    connection, ExternalLookupType.DISPLAY_NAME, "Zhang Kaixuan"));
            assertThrows(ProtocolViolation.class, () -> client.resolveMember(
                    connection, ExternalLookupType.EMAIL, "zhang@example.invalid"));
            assertEquals(exactQueries, stub.memberQueries());
            assertThrows(ProtocolViolation.class,
                    () -> mappings.confirm(false, "member-101", connection, proof));
            assertThrows(ProtocolViolation.class,
                    () -> mappings.confirm(true, "member-101", connection, proof));
            assertThrows(ProtocolViolation.class,
                    () -> mappings.confirm(
                            true, "member-100", connection.withConnection("connection-new", 4),
                            proof));
            assertThrows(ProtocolViolation.class,
                    () -> mappings.confirm(
                            true, "member-100", connection.withTeam("team-other"), proof));

            TenantConnection otherOrganization = connection.withOrganization("organization-2");
            LarkMemberProof otherProof = client.resolveMember(
                    otherOrganization, ExternalLookupType.OPEN_ID, "ou_a_100");
            LarkMemberMapping isolated = mappings.confirm(
                    true, "member-100", otherOrganization, otherProof);
            assertEquals("organization-2", isolated.organizationId());

            TenantConnection wrongTenant = connection.withExpectedTenantKey("tenant-b");
            LarkProviderException mismatch = assertThrows(
                    LarkProviderException.class, () -> client.queryTenant(wrongTenant));
            assertEquals(LarkErrorCode.IDENTITY_MISMATCH, mismatch.code());
        }
    }

    @Test
    void retriesLostResponseWithSameUuidAndQueriesOneSafeExternalReceipt() throws Exception {
        try (LarkStub stub = LarkStub.start()) {
            RecordingSleeper sleeper = new RecordingSleeper();
            LarkClient client = client(stub, credentials(), sleeper);
            NotificationCommand command = reviewCommand("action-digest-lost-response");
            stub.enqueue(SendBehavior.LOSE_RESPONSE);

            LarkDeliveryResult delivered = client.deliver(tenantA(), command);
            LarkReceipt receipt = client.queryReceipt(
                    tenantA(), delivered.messageId(), delivered.idempotencyKey());
            LarkDeliveryResult replay = client.deliver(tenantA(), command);

            assertEquals(delivered.messageId(), replay.messageId());
            assertEquals(DeliveryStatus.ACCEPTED, receipt.status());
            assertEquals(delivered.idempotencyKey(), receipt.idempotencyKey());
            assertEquals(1, stub.providerMessageWrites());
            assertTrue(stub.sendRequests() >= 3);
            assertEquals(1, Set.copyOf(stub.observedUuids()).size());
            assertNotEquals(
                    idempotencyKey(tenantA(), command.actionDigest()),
                    idempotencyKey(tenantB(), command.actionDigest()));
            assertEquals("Work item \"Release\" requires review. Open https://crewscope.invalid/reviews/42",
                    stub.messageText(delivered.messageId()));
            assertFalse((delivered + " " + receipt).contains(TOKEN_MARKER));
            assertFalse((delivered + " " + receipt).contains(stub.baseUri().toString()));
        }
    }

    @Test
    void appliesBoundedRateLimitAndServerRetriesWithoutChangingIdempotencyIdentity()
            throws Exception {
        try (LarkStub stub = LarkStub.start()) {
            RecordingSleeper sleeper = new RecordingSleeper();
            LarkClient client = client(stub, credentials(), sleeper);
            stub.enqueue(SendBehavior.RATE_LIMITED);
            stub.enqueue(SendBehavior.SERVER_ERROR);
            stub.enqueue(SendBehavior.SUCCESS);

            LarkDeliveryResult result = client.deliver(
                    tenantA(), reviewCommand("action-digest-retry"));

            assertEquals(1, stub.providerMessageWrites());
            assertEquals(3, stub.sendRequests());
            assertEquals(1, Set.copyOf(stub.observedUuids()).size());
            assertEquals(List.of(Duration.ofSeconds(7), Duration.ofSeconds(2)), sleeper.delays());
            assertEquals(stub.observedUuids().get(0), result.idempotencyKey());

            stub.resetSendObservations();
            stub.enqueue(SendBehavior.RATE_LIMITED);
            stub.enqueue(SendBehavior.RATE_LIMITED);
            stub.enqueue(SendBehavior.RATE_LIMITED);
            LarkProviderException exhausted = assertThrows(
                    LarkProviderException.class,
                    () -> client.deliver(tenantA(), reviewCommand("action-digest-exhausted")));
            assertEquals(LarkErrorCode.RATE_LIMITED, exhausted.code());
            assertEquals(3, stub.sendRequests());
            assertEquals(0, stub.providerMessageWritesSinceReset());
        }
    }

    @Test
    void failsClosedOnRevocationTemplateDriftAndUnknownVariablesBeforeHttpWrite()
            throws Exception {
        try (LarkStub stub = LarkStub.start()) {
            LarkClient client = client(stub, credentials(), delay -> {});
            TenantConnection connection = tenantA();
            client.queryTenant(connection);
            int requestsBeforeRevocation = stub.totalRequests();

            LarkProviderException revoked = assertThrows(
                    LarkProviderException.class,
                    () -> client.queryTenant(connection.revoke()));
            assertEquals(LarkErrorCode.CONNECTION_UNAVAILABLE, revoked.code());
            assertEquals(requestsBeforeRevocation, stub.totalRequests());

            int sendsBeforeInvalidTemplates = stub.sendRequests();
            assertThrows(ProtocolViolation.class, () -> client.deliver(
                    connection, reviewCommand("template-drift").withTemplateVersion(4)));
            assertThrows(ProtocolViolation.class, () -> client.deliver(
                    connection, reviewCommand("unknown-variable")
                            .withVariable("arbitraryBody", "provider secret")));
            assertThrows(ProtocolViolation.class, () -> client.deliver(
                    connection, reviewCommand("untrusted-link")
                            .withVariable("reviewUrl", "https://evil.invalid/review/42")));
            assertThrows(ProtocolViolation.class, () -> client.deliver(
                    connection, reviewCommand("untrusted-port")
                            .withVariable(
                                    "reviewUrl",
                                    "https://crewscope.invalid:8443/reviews/42")));
            assertEquals(sendsBeforeInvalidTemplates, stub.sendRequests());
        }
    }

    @Test
    void normalizesProviderFailuresAndRejectsUntrustedEndpointsWithoutSensitiveEvidence()
            throws Exception {
        Map<Integer, LarkErrorCode> expected = Map.of(
                401, LarkErrorCode.AUTHENTICATION_REQUIRED,
                403, LarkErrorCode.PERMISSION_DENIED,
                404, LarkErrorCode.RESOURCE_UNAVAILABLE,
                429, LarkErrorCode.RATE_LIMITED,
                500, LarkErrorCode.PROVIDER_UNAVAILABLE,
                503, LarkErrorCode.PROVIDER_UNAVAILABLE);
        expected.forEach((status, code) -> {
            LarkProviderException failure = LarkErrorNormalizer.normalize(
                    status,
                    status == 429 ? Optional.of("7") : Optional.empty(),
                    "{\"msg\":\"" + SECRET_A + " " + TOKEN_MARKER + " "
                            + RAW_PROVIDER_MARKER + "\"}");
            assertEquals(code, failure.code());
            String publicFailure = failure + " " + failure.getMessage();
            assertFalse(publicFailure.contains(SECRET_A));
            assertFalse(publicFailure.contains(TOKEN_MARKER));
            assertFalse(publicFailure.contains(RAW_PROVIDER_MARKER));
            assertFalse(publicFailure.contains("{\"msg\""));
        });

        assertThrows(ProtocolViolation.class, () -> EndpointPolicy.requireAllowed(
                URI.create("http://169.254.169.254/latest/meta-data"), false));
        assertThrows(ProtocolViolation.class, () -> EndpointPolicy.requireAllowed(
                URI.create("https://open.feishu.cn@evil.invalid/open-apis"), false));
        assertThrows(ProtocolViolation.class, () -> EndpointPolicy.requireAllowed(
                URI.create("https://evil.invalid/open-apis"), false));
        assertThrows(ProtocolViolation.class, () -> EndpointPolicy.requireAllowed(
                URI.create("http://127.0.0.1:18080"), false));
        assertEquals(
                URI.create("https://open.feishu.cn"),
                EndpointPolicy.requireAllowed(URI.create("https://open.feishu.cn"), false));

        try (LarkStub stub = LarkStub.start()) {
            assertEquals(stub.baseUri(), EndpointPolicy.requireAllowed(stub.baseUri(), true));
            LarkClient client = client(stub, credentials(), delay -> {});
            stub.enqueue(SendBehavior.FORBIDDEN);
            LarkProviderException denied = assertThrows(
                    LarkProviderException.class,
                    () -> client.deliver(tenantA(), reviewCommand("action-digest-denied")));
            assertEquals(LarkErrorCode.PERMISSION_DENIED, denied.code());
            assertFalse(denied.toString().contains(stub.baseUri().toString()));
        }
    }

    private static CredentialResolver credentials() {
        return new CredentialResolver(Map.of(
                "credential-a", new AppCredential(APP_A, SECRET_A),
                "credential-b", new AppCredential(APP_B, SECRET_B)));
    }

    private static LarkClient client(
            LarkStub stub, CredentialResolver credentials, RetrySleeper sleeper) {
        return new LarkClient(
                stub.baseUri(), true, credentials, HttpClient.newHttpClient(), sleeper,
                () -> NOW, REQUEST_TIMEOUT, 3);
    }

    private static TenantConnection tenantA() {
        return new TenantConnection(
                "organization-1", "team-1", "connection-a", 3, "grant-a", 5,
                "credential-a", 7, "tenant-a", true);
    }

    private static TenantConnection tenantB() {
        return new TenantConnection(
                "organization-1", "team-1", "connection-b", 11, "grant-b", 13,
                "credential-b", 17, "tenant-b", true);
    }

    private static NotificationCommand reviewCommand(String actionDigest) {
        return new NotificationCommand(
                "review-required", 3,
                Map.of(
                        "workItemTitle", "Work item \"Release\"",
                        "reviewUrl", "https://crewscope.invalid/reviews/42"),
                new ExternalUserId("ou_a_100"), actionDigest);
    }

    private enum ExternalLookupType {
        OPEN_ID,
        DISPLAY_NAME,
        EMAIL
    }

    private enum DeliveryStatus {
        ACCEPTED
    }

    private enum SendBehavior {
        SUCCESS,
        LOSE_RESPONSE,
        RATE_LIMITED,
        SERVER_ERROR,
        FORBIDDEN
    }

    private enum LarkErrorCode {
        AUTHENTICATION_REQUIRED,
        PERMISSION_DENIED,
        RESOURCE_UNAVAILABLE,
        RATE_LIMITED,
        PROVIDER_UNAVAILABLE,
        INVALID_RESPONSE,
        IDENTITY_MISMATCH,
        MAPPING_CONFLICT,
        CONNECTION_UNAVAILABLE,
        CANCELLED,
        UNKNOWN_DELIVERY
    }

    private record TenantConnection(
            String organizationId,
            String teamId,
            String connectionId,
            long connectionVersion,
            String grantId,
            long grantVersion,
            String credentialId,
            long credentialVersion,
            String expectedTenantKey,
            boolean active) {

        private TenantConnection {
            requireText(organizationId, "organizationId");
            requireText(teamId, "teamId");
            requireText(connectionId, "connectionId");
            requireText(grantId, "grantId");
            requireText(credentialId, "credentialId");
            requireText(expectedTenantKey, "expectedTenantKey");
            if (connectionVersion < 0 || grantVersion < 0 || credentialVersion < 0) {
                throw new ProtocolViolation("authorization versions must not be negative");
            }
        }

        TenantConnection withExpectedTenantKey(String tenantKey) {
            return new TenantConnection(
                    organizationId, teamId, connectionId, connectionVersion, grantId,
                    grantVersion, credentialId, credentialVersion, tenantKey, active);
        }

        TenantConnection withOrganization(String changedOrganizationId) {
            return new TenantConnection(
                    changedOrganizationId, teamId, connectionId, connectionVersion, grantId,
                    grantVersion, credentialId, credentialVersion, expectedTenantKey, active);
        }

        TenantConnection withTeam(String changedTeamId) {
            return new TenantConnection(
                    organizationId, changedTeamId, connectionId, connectionVersion, grantId,
                    grantVersion, credentialId, credentialVersion, expectedTenantKey, active);
        }

        TenantConnection withConnection(String changedConnectionId, long changedVersion) {
            return new TenantConnection(
                    organizationId, teamId, changedConnectionId, changedVersion, grantId,
                    grantVersion, credentialId, credentialVersion, expectedTenantKey, active);
        }

        TenantConnection revoke() {
            return new TenantConnection(
                    organizationId, teamId, connectionId, connectionVersion + 1, grantId,
                    grantVersion, credentialId, credentialVersion, expectedTenantKey, false);
        }

        TokenCacheKey cacheKey() {
            return new TokenCacheKey(
                    organizationId, connectionId, connectionVersion, grantId, grantVersion,
                    credentialId, credentialVersion, expectedTenantKey);
        }
    }

    private record TokenCacheKey(
            String organizationId,
            String connectionId,
            long connectionVersion,
            String grantId,
            long grantVersion,
            String credentialId,
            long credentialVersion,
            String tenantKey) {}

    private record TenantToken(String value, Instant expiresAt) {

        private TenantToken {
            requireText(value, "tenantToken");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }

        boolean usableAt(Instant now) {
            return expiresAt.minusSeconds(60).isAfter(now);
        }

        @Override
        public String toString() {
            return "TenantToken[REDACTED]";
        }
    }

    private record LarkTenantProof(String tenantKey) {}

    private record LarkMemberProof(
            String organizationId,
            String teamId,
            String connectionId,
            long connectionVersion,
            String grantId,
            long grantVersion,
            String tenantKey,
            String openId,
            String unionId,
            String externalVersion,
            Instant verifiedAt) {}

    private record ExternalUserId(String value) {

        private static final Pattern FORMAT = Pattern.compile("ou_[A-Za-z0-9_-]{1,120}");

        private ExternalUserId {
            if (value == null || !FORMAT.matcher(value).matches()) {
                throw new ProtocolViolation("Lark open_id has an invalid shape");
            }
        }
    }

    private record LarkMemberMapping(
            String organizationId,
            String teamId,
            String internalMemberId,
            String connectionId,
            long connectionVersion,
            String grantId,
            long grantVersion,
            String tenantKey,
            String openId,
            String unionId,
            String providerVersion,
            Instant verifiedAt,
            long mappingVersion) {}

    private static final class MemberMappingRegistry {

        private final Map<String, LarkMemberMapping> byInternalMember = new HashMap<>();
        private final Map<String, LarkMemberMapping> byExternalIdentity = new HashMap<>();

        LarkMemberMapping confirm(
                boolean teamAdmin,
                String internalMemberId,
                TenantConnection connection,
                LarkMemberProof proof) {
            if (!teamAdmin) {
                throw new ProtocolViolation("member mapping requires Team Admin confirmation");
            }
            requireText(internalMemberId, "internalMemberId");
            if (!connection.active()
                    || !connection.expectedTenantKey().equals(proof.tenantKey())) {
                throw new ProtocolViolation("mapping proof does not belong to the active tenant");
            }
            if (!connection.organizationId().equals(proof.organizationId())
                    || !connection.teamId().equals(proof.teamId())
                    || !connection.connectionId().equals(proof.connectionId())
                    || connection.connectionVersion() != proof.connectionVersion()
                    || !connection.grantId().equals(proof.grantId())
                    || connection.grantVersion() != proof.grantVersion()) {
                throw new ProtocolViolation(
                        "mapping proof does not belong to the current Connection and Team scope");
            }
            String internalKey = scopedKey(
                    connection.organizationId(), connection.teamId(), internalMemberId);
            String externalKey = scopedKey(
                    connection.organizationId(), proof.tenantKey(), proof.openId());
            LarkMemberMapping existingExternal = byExternalIdentity.get(externalKey);
            if (existingExternal != null
                    && !existingExternal.internalMemberId().equals(internalMemberId)) {
                throw new ProtocolViolation("external Lark user is already mapped");
            }
            LarkMemberMapping existingInternal = byInternalMember.get(internalKey);
            if (existingInternal != null
                    && (!existingInternal.tenantKey().equals(proof.tenantKey())
                            || !existingInternal.openId().equals(proof.openId()))) {
                throw new ProtocolViolation("CrewScope member already has a different mapping");
            }
            if (existingInternal != null) {
                return existingInternal;
            }
            LarkMemberMapping mapping = new LarkMemberMapping(
                    connection.organizationId(), connection.teamId(), internalMemberId,
                    connection.connectionId(), connection.connectionVersion(),
                    connection.grantId(), connection.grantVersion(), proof.tenantKey(),
                    proof.openId(), proof.unionId(), proof.externalVersion(), proof.verifiedAt(), 1);
            byInternalMember.put(internalKey, mapping);
            byExternalIdentity.put(externalKey, mapping);
            return mapping;
        }

        private static String scopedKey(String... values) {
            StringBuilder key = new StringBuilder();
            for (String value : values) {
                key.append('|').append(value.length()).append(':').append(value);
            }
            return key.toString();
        }
    }

    private record AppCredential(String appId, String appSecret) {

        private AppCredential {
            requireText(appId, "appId");
            requireText(appSecret, "appSecret");
        }

        @Override
        public String toString() {
            return "AppCredential[appId=REDACTED, appSecret=REDACTED]";
        }
    }

    /** Test double for an ADR-004 CredentialStore action handle. */
    private static final class CredentialHandle implements AutoCloseable {

        private final String appId;
        private char[] secret;
        private boolean closed;

        CredentialHandle(AppCredential credential) {
            this.appId = credential.appId();
            this.secret = credential.appSecret().toCharArray();
        }

        String appId() {
            requireOpen();
            return appId;
        }

        char[] copySecret() {
            requireOpen();
            return secret.clone();
        }

        boolean closed() {
            return closed;
        }

        @Override
        public void close() {
            if (!closed) {
                Arrays.fill(secret, '\0');
                secret = null;
                closed = true;
            }
        }

        @Override
        public String toString() {
            return "CredentialHandle[REDACTED]";
        }

        private void requireOpen() {
            if (closed) {
                throw new IllegalStateException("credential handle is closed");
            }
        }
    }

    private static final class CredentialResolver {

        private final Map<String, AppCredential> credentials;
        private final List<CredentialHandle> resolved = new ArrayList<>();

        CredentialResolver(Map<String, AppCredential> credentials) {
            this.credentials = Map.copyOf(credentials);
        }

        synchronized CredentialHandle resolve(TenantConnection connection) {
            if (!connection.active()) {
                throw new LarkProviderException(
                        LarkErrorCode.CONNECTION_UNAVAILABLE, "Lark connection is unavailable");
            }
            AppCredential credential = credentials.get(connection.credentialId());
            if (credential == null) {
                throw new LarkProviderException(
                        LarkErrorCode.CONNECTION_UNAVAILABLE, "Lark credential is unavailable");
            }
            CredentialHandle handle = new CredentialHandle(credential);
            resolved.add(handle);
            return handle;
        }

        int resolveCount() {
            return resolved.size();
        }

        long closedHandleCount() {
            return resolved.stream().filter(CredentialHandle::closed).count();
        }

        @Override
        public String toString() {
            return "CredentialResolver[credentials=REDACTED]";
        }
    }

    private record NotificationCommand(
            String templateId,
            long templateVersion,
            Map<String, String> variables,
            ExternalUserId recipient,
            String actionDigest) {

        private NotificationCommand {
            requireText(templateId, "templateId");
            variables = Map.copyOf(variables);
            Objects.requireNonNull(recipient, "recipient");
            requireText(actionDigest, "actionDigest");
        }

        NotificationCommand withTemplateVersion(long version) {
            return new NotificationCommand(
                    templateId, version, variables, recipient, actionDigest);
        }

        NotificationCommand withVariable(String name, String value) {
            Map<String, String> changed = new HashMap<>(variables);
            changed.put(name, value);
            return new NotificationCommand(
                    templateId, templateVersion, changed, recipient, actionDigest);
        }
    }

    private record RenderedMessage(String messageType, String contentJson) {}

    private static final class FixedTemplateRegistry {

        RenderedMessage render(NotificationCommand command) {
            if (!command.templateId().equals("review-required")
                    || command.templateVersion() != 3
                    || !command.variables().keySet().equals(Set.of("workItemTitle", "reviewUrl"))) {
                throw new ProtocolViolation("fixed notification template or variable schema is invalid");
            }
            String title = requireBoundedVariable(
                    command.variables().get("workItemTitle"), "workItemTitle", 200);
            String reviewUrl = requireBoundedVariable(
                    command.variables().get("reviewUrl"), "reviewUrl", 500);
            URI link;
            try {
                link = URI.create(reviewUrl);
            } catch (IllegalArgumentException exception) {
                throw new ProtocolViolation("reviewUrl is invalid");
            }
            if (!"https".equals(link.getScheme())
                    || !"crewscope.invalid".equals(link.getHost())
                    || link.getPort() != -1
                    || link.getUserInfo() != null) {
                throw new ProtocolViolation("reviewUrl must use the trusted CrewScope origin");
            }
            try {
                String text = title + " requires review. Open " + reviewUrl;
                return new RenderedMessage(
                        "text", JSON.writeValueAsString(Map.of("text", text)));
            } catch (RuntimeException exception) {
                throw new ProtocolViolation("fixed notification template could not be rendered");
            }
        }
    }

    private record LarkDeliveryResult(String messageId, String idempotencyKey) {}

    private record LarkReceipt(
            String messageId,
            String idempotencyKey,
            DeliveryStatus status,
            String evidenceCode) {}

    @FunctionalInterface
    private interface RetrySleeper {
        void pause(Duration duration);
    }

    private static final class RecordingSleeper implements RetrySleeper {

        private final List<Duration> delays = new ArrayList<>();

        @Override
        public void pause(Duration duration) {
            delays.add(duration);
        }

        List<Duration> delays() {
            return List.copyOf(delays);
        }
    }

    @FunctionalInterface
    private interface TimeSource {
        Instant now();
    }

    /** Test-only client that keeps all endpoints fixed and all tenant tokens cache-scope bound. */
    private static final class LarkClient {

        private static final String TOKEN_PATH =
                "/open-apis/auth/v3/tenant_access_token/internal";
        private static final String TENANT_PATH = "/open-apis/tenant/v2/tenant/query";
        private static final String MEMBER_PREFIX = "/open-apis/contact/v3/users/";
        private static final String MESSAGE_PATH =
                "/open-apis/im/v1/messages?receive_id_type=open_id";

        private final URI baseUri;
        private final CredentialResolver credentials;
        private final HttpClient http;
        private final RetrySleeper sleeper;
        private final TimeSource time;
        private final Duration requestTimeout;
        private final int maximumAttempts;
        private final FixedTemplateRegistry templates = new FixedTemplateRegistry();
        private final Map<TokenCacheKey, TenantToken> tokenCache = new HashMap<>();

        LarkClient(
                URI baseUri,
                boolean loopbackEnabled,
                CredentialResolver credentials,
                HttpClient http,
                RetrySleeper sleeper,
                TimeSource time,
                Duration requestTimeout,
                int maximumAttempts) {
            this.baseUri = EndpointPolicy.requireAllowed(baseUri, loopbackEnabled);
            this.credentials = Objects.requireNonNull(credentials, "credentials");
            this.http = Objects.requireNonNull(http, "http");
            this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
            this.time = Objects.requireNonNull(time, "time");
            this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
            if (maximumAttempts < 1 || maximumAttempts > 5) {
                throw new ProtocolViolation("maximumAttempts must be between 1 and 5");
            }
            this.maximumAttempts = maximumAttempts;
        }

        LarkTenantProof queryTenant(TenantConnection connection) {
            HttpResponse<String> response = safeAuthorizedRead(
                    connection, "GET", TENANT_PATH, Optional.empty());
            JsonNode data = requireSuccess(response).path("data").path("tenant");
            String tenantKey = requireResponseText(data, "tenant_key");
            if (!connection.expectedTenantKey().equals(tenantKey)) {
                throw new LarkProviderException(
                        LarkErrorCode.IDENTITY_MISMATCH,
                        "Lark tenant identity does not match the Connection");
            }
            return new LarkTenantProof(tenantKey);
        }

        LarkMemberProof resolveMember(
                TenantConnection connection, ExternalLookupType lookupType, String externalValue) {
            if (lookupType != ExternalLookupType.OPEN_ID) {
                throw new ProtocolViolation(
                        "member validation requires an exact Lark open_id");
            }
            ExternalUserId openId = new ExternalUserId(externalValue);
            LarkTenantProof tenant = queryTenant(connection);
            HttpResponse<String> response = safeAuthorizedRead(
                    connection,
                    "GET",
                    MEMBER_PREFIX + openId.value()
                            + "?user_id_type=open_id&department_id_type=open_department_id",
                    Optional.empty());
            JsonNode user = requireSuccess(response).path("data").path("user");
            String resolvedOpenId = requireResponseText(user, "open_id");
            if (!openId.value().equals(resolvedOpenId)) {
                throw new LarkProviderException(
                        LarkErrorCode.IDENTITY_MISMATCH,
                        "Lark member identity does not match the exact lookup");
            }
            return new LarkMemberProof(
                    connection.organizationId(), connection.teamId(), connection.connectionId(),
                    connection.connectionVersion(), connection.grantId(),
                    connection.grantVersion(), tenant.tenantKey(), resolvedOpenId,
                    requireResponseText(user, "union_id"),
                    requireResponseText(user, "version"), time.now());
        }

        LarkDeliveryResult deliver(TenantConnection connection, NotificationCommand command) {
            requireActive(connection);
            RenderedMessage rendered = templates.render(command);
            String idempotencyKey = idempotencyKey(connection, command.actionDigest());
            String body = JSON.writeValueAsString(Map.of(
                    "receive_id", command.recipient().value(),
                    "msg_type", rendered.messageType(),
                    "content", rendered.contentJson(),
                    "uuid", idempotencyKey));
            LarkProviderException lastFailure = null;
            for (int attempt = 1; attempt <= maximumAttempts; attempt++) {
                try {
                    HttpResponse<String> response = authorized(
                            connection, "POST", MESSAGE_PATH, Optional.of(body));
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        JsonNode data = requireSuccess(response).path("data");
                        String messageId = requireResponseText(data, "message_id");
                        return new LarkDeliveryResult(messageId, idempotencyKey);
                    }
                    lastFailure = LarkErrorNormalizer.normalize(
                            response.statusCode(), response.headers().firstValue("Retry-After"),
                            response.body());
                    if (!isTransient(lastFailure.code()) || attempt == maximumAttempts) {
                        throw lastFailure;
                    }
                    sleeper.pause(retryDelay(lastFailure, attempt));
                } catch (LarkProviderException failure) {
                    throw failure;
                } catch (TransportRuntimeException failure) {
                    lastFailure = new LarkProviderException(
                            LarkErrorCode.UNKNOWN_DELIVERY,
                            "Lark delivery result is unknown after transport failure");
                    if (attempt == maximumAttempts) {
                        throw lastFailure;
                    }
                }
            }
            throw Objects.requireNonNull(lastFailure, "lastFailure");
        }

        LarkReceipt queryReceipt(
                TenantConnection connection, String messageId, String idempotencyKey) {
            if (messageId == null || !messageId.matches("om_[A-Za-z0-9_-]{1,120}")) {
                throw new ProtocolViolation("Lark message_id has an invalid shape");
            }
            if (idempotencyKey == null || !idempotencyKey.matches("[0-9a-f]{32}")) {
                throw new ProtocolViolation("Lark notification uuid has an invalid shape");
            }
            HttpResponse<String> response = safeAuthorizedRead(
                    connection, "GET", "/open-apis/im/v1/messages/" + messageId,
                    Optional.empty());
            JsonNode items = requireSuccess(response).path("data").path("items");
            if (!items.isArray() || items.size() != 1) {
                throw new LarkProviderException(
                        LarkErrorCode.INVALID_RESPONSE,
                        "Lark receipt query must return one exact message");
            }
            String resolvedMessageId = requireResponseText(items.path(0), "message_id");
            if (!messageId.equals(resolvedMessageId)) {
                throw new LarkProviderException(
                        LarkErrorCode.INVALID_RESPONSE,
                        "Lark receipt identity does not match the requested message");
            }
            return new LarkReceipt(
                    resolvedMessageId,
                    idempotencyKey,
                    DeliveryStatus.ACCEPTED,
                    "LARK_MESSAGE_EXISTS");
        }

        String safeSummary() {
            return "LarkClient[provider=lark, cachedTenants=" + tokenCache.size() + "]";
        }

        private HttpResponse<String> safeAuthorizedRead(
                TenantConnection connection,
                String method,
                String pathAndQuery,
                Optional<String> body) {
            try {
                return authorized(connection, method, pathAndQuery, body);
            } catch (TransportRuntimeException failure) {
                throw new LarkProviderException(
                        LarkErrorCode.PROVIDER_UNAVAILABLE,
                        "Lark read request is unavailable after a transport failure");
            }
        }

        private HttpResponse<String> authorized(
                TenantConnection connection,
                String method,
                String pathAndQuery,
                Optional<String> body) {
            requireActive(connection);
            for (int authenticationAttempt = 0; authenticationAttempt < 2;
                    authenticationAttempt++) {
                TenantToken token = token(connection);
                try {
                    HttpRequest.Builder request = HttpRequest.newBuilder(baseUri.resolve(pathAndQuery))
                            .timeout(requestTimeout)
                            .header("Authorization", "Bearer " + token.value())
                            .header("Accept", "application/json");
                    if (method.equals("POST")) {
                        request.header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(body.orElseThrow()));
                    } else {
                        request.GET();
                    }
                    HttpResponse<String> response = http.send(
                            request.build(), HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() != 401 || authenticationAttempt == 1) {
                        return response;
                    }
                    tokenCache.remove(connection.cacheKey());
                } catch (HttpTimeoutException timeout) {
                    throw new TransportRuntimeException(timeout);
                } catch (IOException failure) {
                    throw new TransportRuntimeException(failure);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new LarkProviderException(
                            LarkErrorCode.CANCELLED, "Lark request was cancelled");
                }
            }
            throw new LarkProviderException(
                    LarkErrorCode.AUTHENTICATION_REQUIRED,
                    "Lark authentication could not be refreshed");
        }

        private TenantToken token(TenantConnection connection) {
            TokenCacheKey key = connection.cacheKey();
            TenantToken cached = tokenCache.get(key);
            if (cached != null && cached.usableAt(time.now())) {
                return cached;
            }
            TenantToken refreshed = exchangeToken(connection);
            tokenCache.put(key, refreshed);
            return refreshed;
        }

        private TenantToken exchangeToken(TenantConnection connection) {
            try (CredentialHandle credential = credentials.resolve(connection)) {
                char[] secret = credential.copySecret();
                try {
                    String body = JSON.writeValueAsString(Map.of(
                            "app_id", credential.appId(),
                            "app_secret", new String(secret)));
                    HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(TOKEN_PATH))
                            .timeout(requestTimeout)
                            .header("Content-Type", "application/json")
                            .header("Accept", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build();
                    HttpResponse<String> response = http.send(
                            request, HttpResponse.BodyHandlers.ofString());
                    JsonNode root = requireSuccess(response);
                    String value = requireResponseText(root, "tenant_access_token");
                    long expireSeconds = root.path("expire").asLong();
                    if (expireSeconds < 120 || expireSeconds > 86_400) {
                        throw new LarkProviderException(
                                LarkErrorCode.INVALID_RESPONSE,
                                "Lark tenant token expiry is outside the accepted range");
                    }
                    return new TenantToken(value, time.now().plusSeconds(expireSeconds));
                } finally {
                    Arrays.fill(secret, '\0');
                }
            } catch (LarkProviderException failure) {
                throw failure;
            } catch (IOException failure) {
                throw new LarkProviderException(
                        LarkErrorCode.PROVIDER_UNAVAILABLE,
                        "Lark tenant token endpoint is unavailable");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new LarkProviderException(
                        LarkErrorCode.CANCELLED, "Lark token exchange was cancelled");
            }
        }

        private JsonNode requireSuccess(HttpResponse<String> response) {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw LarkErrorNormalizer.normalize(
                        response.statusCode(), response.headers().firstValue("Retry-After"),
                        response.body());
            }
            try {
                JsonNode root = JSON.readTree(response.body());
                if (root.path("code").asInt(-1) != 0) {
                    throw new LarkProviderException(
                            LarkErrorCode.INVALID_RESPONSE,
                            "Lark response contains a non-success provider code");
                }
                return root;
            } catch (LarkProviderException failure) {
                throw failure;
            } catch (RuntimeException failure) {
                throw new LarkProviderException(
                        LarkErrorCode.INVALID_RESPONSE,
                        "Lark response is not valid bounded JSON");
            }
        }

        private static void requireActive(TenantConnection connection) {
            if (!connection.active()) {
                throw new LarkProviderException(
                        LarkErrorCode.CONNECTION_UNAVAILABLE,
                        "Lark connection or grant is unavailable");
            }
        }

        private static String requireResponseText(JsonNode node, String field) {
            JsonNode valueNode = node.path(field);
            if (!valueNode.isString()
                    || valueNode.stringValue().isBlank()
                    || valueNode.stringValue().length() > 500) {
                throw new LarkProviderException(
                        LarkErrorCode.INVALID_RESPONSE,
                        "Lark response is missing a required bounded field");
            }
            return valueNode.stringValue();
        }

        private static boolean isTransient(LarkErrorCode code) {
            return code == LarkErrorCode.RATE_LIMITED
                    || code == LarkErrorCode.PROVIDER_UNAVAILABLE;
        }

        private static Duration retryDelay(LarkProviderException failure, int attempt) {
            return failure.retryAfter().orElse(
                    Duration.ofSeconds(Math.min(1L << Math.max(0, attempt - 1), 30)));
        }
    }

    private static final class EndpointPolicy {

        static URI requireAllowed(URI endpoint, boolean loopbackEnabled) {
            URI required = Objects.requireNonNull(endpoint, "endpoint").normalize();
            boolean exactProduction = "https".equals(required.getScheme())
                    && "open.feishu.cn".equals(required.getHost())
                    && required.getPort() == -1;
            boolean exactLoopback = loopbackEnabled
                    && "http".equals(required.getScheme())
                    && isLiteralLoopback(required.getHost())
                    && required.getPort() > 0;
            boolean cleanAuthority = required.getUserInfo() == null
                    && required.getQuery() == null
                    && required.getFragment() == null
                    && (required.getPath().isEmpty() || required.getPath().equals("/"));
            if ((!exactProduction && !exactLoopback) || !cleanAuthority) {
                throw new ProtocolViolation("Lark endpoint is not on the fixed allowlist");
            }
            return URI.create(required.getScheme() + "://" + required.getHost()
                    + (required.getPort() == -1 ? "" : ":" + required.getPort()));
        }

        private static boolean isLiteralLoopback(String host) {
            if (host == null || !(host.equals("127.0.0.1") || host.equals("::1")
                    || host.equals("[::1]"))) {
                return false;
            }
            try {
                return InetAddress.getByName(host.replace("[", "").replace("]", ""))
                        .isLoopbackAddress();
            } catch (IOException failure) {
                return false;
            }
        }
    }

    private static final class LarkErrorNormalizer {

        static LarkProviderException normalize(
                int status, Optional<String> retryAfterHeader, String ignoredRawBody) {
            Objects.requireNonNull(retryAfterHeader, "retryAfterHeader");
            Objects.requireNonNull(ignoredRawBody, "rawBody");
            LarkErrorCode code = switch (status) {
                case 401 -> LarkErrorCode.AUTHENTICATION_REQUIRED;
                case 403 -> LarkErrorCode.PERMISSION_DENIED;
                case 404 -> LarkErrorCode.RESOURCE_UNAVAILABLE;
                case 429 -> LarkErrorCode.RATE_LIMITED;
                default -> status >= 500
                        ? LarkErrorCode.PROVIDER_UNAVAILABLE
                        : LarkErrorCode.INVALID_RESPONSE;
            };
            Optional<Duration> retryAfter = code == LarkErrorCode.RATE_LIMITED
                    ? retryAfterHeader.flatMap(LarkErrorNormalizer::parseRetryAfter)
                    : Optional.empty();
            return new LarkProviderException(
                    code, "Lark provider request failed with a safe status category", retryAfter);
        }

        private static Optional<Duration> parseRetryAfter(String value) {
            try {
                long seconds = Long.parseLong(value);
                return seconds > 0 && seconds <= 300
                        ? Optional.of(Duration.ofSeconds(seconds))
                        : Optional.empty();
            } catch (NumberFormatException failure) {
                return Optional.empty();
            }
        }
    }

    private static final class LarkProviderException extends RuntimeException {

        private final LarkErrorCode code;
        private final Optional<Duration> retryAfter;

        LarkProviderException(LarkErrorCode code, String safeMessage) {
            this(code, safeMessage, Optional.empty());
        }

        LarkProviderException(
                LarkErrorCode code, String safeMessage, Optional<Duration> retryAfter) {
            super(safeMessage);
            this.code = Objects.requireNonNull(code, "code");
            this.retryAfter = Objects.requireNonNull(retryAfter, "retryAfter");
        }

        LarkErrorCode code() {
            return code;
        }

        Optional<Duration> retryAfter() {
            return retryAfter;
        }
    }

    private static final class TransportRuntimeException extends RuntimeException {

        TransportRuntimeException(IOException ignoredCause) {
            super("Lark transport failed");
        }
    }

    private static final class ProtocolViolation extends RuntimeException {

        ProtocolViolation(String message) {
            super(message);
        }
    }

    /** Deterministic Lark OpenAPI stub with provider-side UUID deduplication. */
    private static final class LarkStub implements AutoCloseable {

        private final HttpServer server;
        private final ExecutorService executor;
        private final URI baseUri;
        private final Map<String, TenantDefinition> applications;
        private final Map<String, TenantDefinition> issuedTokens = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> tokenRequests = new ConcurrentHashMap<>();
        private final Map<String, Boolean> rejectNextTenant = new ConcurrentHashMap<>();
        private final Map<String, StoredMessage> messagesByUuid = new ConcurrentHashMap<>();
        private final Map<String, StoredMessage> messagesById = new ConcurrentHashMap<>();
        private final Queue<SendBehavior> sendBehaviors = new ArrayDeque<>();
        private final List<String> observedUuids = new CopyOnWriteArrayList<>();
        private final AtomicInteger totalRequests = new AtomicInteger();
        private final AtomicInteger memberQueries = new AtomicInteger();
        private final AtomicInteger sendRequests = new AtomicInteger();
        private final AtomicInteger providerWrites = new AtomicInteger();
        private volatile int providerWritesAtReset;

        private LarkStub(HttpServer server, ExecutorService executor) {
            this.server = server;
            this.executor = executor;
            this.baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            this.applications = Map.of(
                    APP_A, new TenantDefinition(
                            APP_A, SECRET_A, "tenant-a",
                            Map.of("ou_a_100", new Member("ou_a_100", "on_a_100", "v3"))),
                    APP_B, new TenantDefinition(
                            APP_B, SECRET_B, "tenant-b",
                            Map.of("ou_b_200", new Member("ou_b_200", "on_b_200", "v5"))));
        }

        static LarkStub start() throws IOException {
            HttpServer server = HttpServer.create(
                    new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
            ExecutorService executor = Executors.newCachedThreadPool();
            LarkStub stub = new LarkStub(server, executor);
            server.createContext("/", stub::handle);
            server.setExecutor(executor);
            server.start();
            return stub;
        }

        URI baseUri() {
            return baseUri;
        }

        synchronized void enqueue(SendBehavior behavior) {
            sendBehaviors.add(behavior);
        }

        void rejectNextAuthorizedRequest(String tenantKey) {
            rejectNextTenant.put(tenantKey, true);
        }

        int tokenRequests(String appId) {
            return tokenRequests.getOrDefault(appId, new AtomicInteger()).get();
        }

        int totalRequests() {
            return totalRequests.get();
        }

        int memberQueries() {
            return memberQueries.get();
        }

        int sendRequests() {
            return sendRequests.get();
        }

        int providerMessageWrites() {
            return providerWrites.get();
        }

        int providerMessageWritesSinceReset() {
            return providerWrites.get() - providerWritesAtReset;
        }

        List<String> observedUuids() {
            return List.copyOf(observedUuids);
        }

        String messageText(String messageId) {
            StoredMessage message = messagesById.get(messageId);
            return message == null ? "" : message.text();
        }

        void resetSendObservations() {
            sendRequests.set(0);
            observedUuids.clear();
            providerWritesAtReset = providerWrites.get();
        }

        private void handle(HttpExchange exchange) throws IOException {
            totalRequests.incrementAndGet();
            try {
                String path = exchange.getRequestURI().getPath();
                if (path.equals("/open-apis/auth/v3/tenant_access_token/internal")) {
                    handleToken(exchange);
                    return;
                }
                TenantDefinition tenant = authorize(exchange);
                if (tenant == null) {
                    return;
                }
                if (path.equals("/open-apis/tenant/v2/tenant/query")) {
                    sendJson(exchange, 200, Map.of(
                            "code", 0,
                            "msg", "ok",
                            "data", Map.of("tenant", Map.of("tenant_key", tenant.tenantKey()))));
                    return;
                }
                if (path.startsWith("/open-apis/contact/v3/users/")) {
                    handleMember(exchange, tenant, path.substring(path.lastIndexOf('/') + 1));
                    return;
                }
                if (path.equals("/open-apis/im/v1/messages")) {
                    handleSend(exchange, tenant);
                    return;
                }
                if (path.startsWith("/open-apis/im/v1/messages/")) {
                    handleReceipt(exchange, tenant, path.substring(path.lastIndexOf('/') + 1));
                    return;
                }
                sendRawFailure(exchange, 404);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                exchange.close();
            } catch (RuntimeException failure) {
                sendRawFailure(exchange, 500);
            }
        }

        private void handleToken(HttpExchange exchange) throws IOException {
            JsonNode request = JSON.readTree(exchange.getRequestBody());
            String appId = jsonText(request, "app_id");
            String secret = jsonText(request, "app_secret");
            TenantDefinition tenant = applications.get(appId);
            tokenRequests.computeIfAbsent(appId, ignored -> new AtomicInteger()).incrementAndGet();
            if (tenant == null || !tenant.appSecret().equals(secret)) {
                sendRawFailure(exchange, 401);
                return;
            }
            String token = TOKEN_MARKER + "_" + tenant.tenantKey() + "_"
                    + tokenRequests(appId);
            issuedTokens.put(token, tenant);
            sendJson(exchange, 200, Map.of(
                    "code", 0,
                    "msg", "ok",
                    "tenant_access_token", token,
                    "expire", 7_200));
        }

        private TenantDefinition authorize(HttpExchange exchange) throws IOException {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            String token = authorization != null && authorization.startsWith("Bearer ")
                    ? authorization.substring("Bearer ".length())
                    : "";
            TenantDefinition tenant = issuedTokens.get(token);
            if (tenant == null) {
                sendRawFailure(exchange, 401);
                return null;
            }
            if (Boolean.TRUE.equals(rejectNextTenant.remove(tenant.tenantKey()))) {
                sendRawFailure(exchange, 401);
                return null;
            }
            return tenant;
        }

        private void handleMember(
                HttpExchange exchange, TenantDefinition tenant, String openId) throws IOException {
            memberQueries.incrementAndGet();
            Member member = tenant.members().get(openId);
            if (member == null) {
                sendRawFailure(exchange, 404);
                return;
            }
            sendJson(exchange, 200, Map.of(
                    "code", 0,
                    "msg", "ok",
                    "data", Map.of("user", Map.of(
                            "open_id", member.openId(),
                            "union_id", member.unionId(),
                            "version", member.version()))));
        }

        private void handleSend(HttpExchange exchange, TenantDefinition tenant)
                throws IOException, InterruptedException {
            sendRequests.incrementAndGet();
            JsonNode request = JSON.readTree(exchange.getRequestBody());
            String uuid = jsonText(request, "uuid");
            observedUuids.add(uuid);
            SendBehavior behavior;
            synchronized (this) {
                behavior = sendBehaviors.isEmpty()
                        ? SendBehavior.SUCCESS
                        : sendBehaviors.remove();
            }
            if (behavior == SendBehavior.RATE_LIMITED) {
                exchange.getResponseHeaders().add("Retry-After", "7");
                sendRawFailure(exchange, 429);
                return;
            }
            if (behavior == SendBehavior.SERVER_ERROR) {
                sendRawFailure(exchange, 503);
                return;
            }
            if (behavior == SendBehavior.FORBIDDEN) {
                sendRawFailure(exchange, 403);
                return;
            }
            String receiveIdType = queryParameter(exchange.getRequestURI(), "receive_id_type");
            String receiveId = jsonText(request, "receive_id");
            if (!"open_id".equals(receiveIdType)
                    || !tenant.members().containsKey(receiveId)
                    || !"text".equals(jsonText(request, "msg_type"))
                    || uuid.isBlank()) {
                sendRawFailure(exchange, 400);
                return;
            }
            String text = jsonText(JSON.readTree(jsonText(request, "content")), "text");
            StoredMessage message = messagesByUuid.computeIfAbsent(uuid, ignored -> {
                String messageId = "om_" + String.format("%08d", providerWrites.incrementAndGet());
                StoredMessage created = new StoredMessage(
                        tenant.tenantKey(), messageId, uuid, receiveId, text);
                messagesById.put(messageId, created);
                return created;
            });
            if (behavior == SendBehavior.LOSE_RESPONSE) {
                Thread.sleep(300);
            }
            sendJson(exchange, 200, Map.of(
                    "code", 0,
                    "msg", "ok",
                    "data", Map.of("message_id", message.messageId())));
        }

        private void handleReceipt(
                HttpExchange exchange, TenantDefinition tenant, String messageId)
                throws IOException {
            StoredMessage message = messagesById.get(messageId);
            if (message == null || !message.tenantKey().equals(tenant.tenantKey())) {
                sendRawFailure(exchange, 404);
                return;
            }
            sendJson(exchange, 200, Map.of(
                    "code", 0,
                    "msg", "ok",
                    "data", Map.of("items", List.of(Map.of(
                            "message_id", message.messageId(),
                            "msg_type", "text",
                            "deleted", false)))));
        }

        private static void sendRawFailure(HttpExchange exchange, int status) throws IOException {
            sendJson(exchange, status, Map.of(
                    "code", status,
                    "msg", SECRET_A + " " + TOKEN_MARKER + " " + RAW_PROVIDER_MARKER));
        }

        private static void sendJson(HttpExchange exchange, int status, Object body)
                throws IOException {
            byte[] payload = JSON.writeValueAsBytes(body);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    private record TenantDefinition(
            String appId,
            String appSecret,
            String tenantKey,
            Map<String, Member> members) {}

    private record Member(String openId, String unionId, String version) {}

    private record StoredMessage(
            String tenantKey,
            String messageId,
            String uuid,
            String recipientOpenId,
            String text) {}

    private static String jsonText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() ? value.stringValue() : "";
    }

    private static String queryParameter(URI uri, String name) {
        if (uri.getRawQuery() == null) {
            return "";
        }
        for (String pair : uri.getRawQuery().split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts[0].equals(name)) {
                return parts.length == 2 ? parts[1] : "";
            }
        }
        return "";
    }

    private static String idempotencyKey(
            TenantConnection connection, String actionDigest) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(scopedDigestInput(
                                    "lark-notification-v2",
                                    connection.organizationId(),
                                    connection.connectionId(),
                                    Long.toString(connection.connectionVersion()),
                                    connection.expectedTenantKey(),
                                    actionDigest)
                            .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    private static String scopedDigestInput(String... values) {
        StringBuilder canonical = new StringBuilder();
        for (String value : values) {
            String required = Objects.requireNonNull(value, "digest coordinate");
            canonical.append('|').append(required.length()).append(':').append(required);
        }
        return canonical.toString();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ProtocolViolation(field + " is required");
        }
        return value;
    }

    private static String requireBoundedVariable(String value, String field, int maximumLength) {
        String required = requireText(value, field);
        if (required.length() > maximumLength) {
            throw new ProtocolViolation(field + " exceeds its fixed template limit");
        }
        return required;
    }
}
