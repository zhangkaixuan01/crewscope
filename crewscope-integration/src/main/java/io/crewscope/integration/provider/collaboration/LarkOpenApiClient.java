package io.crewscope.integration.provider.collaboration;

import io.crewscope.application.observability.OperationalTelemetry;
import io.crewscope.domain.collaboration.LarkCollaborationCapabilities;
import io.crewscope.domain.collaboration.LarkOpenId;
import io.crewscope.domain.collaboration.LarkProviderVersion;
import io.crewscope.domain.collaboration.LarkTenantKey;
import io.crewscope.domain.collaboration.LarkUnionId;
import io.crewscope.domain.provider.ProviderCapabilities;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.util.Assert;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Fixed-operation Lark OpenAPI transport with exact endpoints and authorization-scoped tokens.
 *
 * <p>The client has no generic method/URL/body entry point. It performs one precise 401 refresh;
 * rate limiting and Provider availability are normalized for the durable Worker state machine.
 */
public final class LarkOpenApiClient implements AutoCloseable {

    private static final String TOKEN_PATH =
            "/open-apis/auth/v3/tenant_access_token/internal";
    private static final String TENANT_PATH = "/open-apis/tenant/v2/tenant/query";
    private static final String MEMBER_PREFIX = "/open-apis/contact/v3/users/";
    private static final String MESSAGE_PATH =
            "/open-apis/im/v1/messages?receive_id_type=open_id";
    private static final String MESSAGE_PREFIX = "/open-apis/im/v1/messages/";
    private static final int MAX_CREDENTIAL_BYTES = 16 * 1024;
    private static final LarkProviderVersion MEMBER_PROVIDER_VERSION =
            new LarkProviderVersion("contact-user-open-api-v1");

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI baseUri;
    private final Duration requestTimeout;
    private final int maximumResponseBytes;
    private final TimeProvider timeProvider;
    private final LarkCredentialAccessManager accessManager;
    private final LarkTenantTokenCache tokenCache;
    private final OperationalTelemetry telemetry;

    public LarkOpenApiClient(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            URI baseUri,
            boolean allowLoopbackHttp,
            Duration requestTimeout,
            int maximumResponseBytes,
            TimeProvider timeProvider,
            LarkCredentialAccessManager accessManager,
            LarkTenantTokenCache tokenCache) {
        this(
                httpClient,
                objectMapper,
                baseUri,
                allowLoopbackHttp,
                requestTimeout,
                maximumResponseBytes,
                timeProvider,
                accessManager,
                tokenCache,
                OperationalTelemetry.noop());
    }

    public LarkOpenApiClient(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            URI baseUri,
            boolean allowLoopbackHttp,
            Duration requestTimeout,
            int maximumResponseBytes,
            TimeProvider timeProvider,
            LarkCredentialAccessManager accessManager,
            LarkTenantTokenCache tokenCache,
            OperationalTelemetry telemetry) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.baseUri = LarkEndpointPolicy.requireAllowed(baseUri, allowLoopbackHttp);
        this.requestTimeout = requireTimeout(requestTimeout);
        if (maximumResponseBytes < 1_024 || maximumResponseBytes > 1_048_576) {
            throw new IllegalArgumentException(
                    "Lark response bytes must be between 1024 and 1048576");
        }
        this.maximumResponseBytes = maximumResponseBytes;
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
        this.accessManager = Objects.requireNonNull(accessManager, "accessManager");
        this.tokenCache = Objects.requireNonNull(tokenCache, "tokenCache");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
    }

    /** Verifies the authenticated tenant and rejects a configured tenant mismatch. */
    public TenantResponse queryTenant(LarkApiCallContext context) {
        OperationalTelemetry.Observation observation = observe(Operation.TENANT_QUERY);
        try (HttpResult result = authorized(
                context, Operation.TENANT_QUERY, TENANT_PATH, null)) {
            JsonNode root = successJson(result, false);
            LarkTenantKey tenantKey = new LarkTenantKey(requireText(
                    root.path("data").path("tenant"), "tenant_key", 128));
            if (!context.authorization().expectedTenantKey().equals(tenantKey)) {
                throw LarkProviderException.of(
                        LarkProviderErrorCode.IDENTITY_MISMATCH,
                        "Lark tenant identity does not match the Connection",
                        "LARK_TENANT_IDENTITY_MISMATCH");
            }
            TenantResponse response = new TenantResponse(tenantKey, timeProvider.now());
            observation.succeed();
            return response;
        } catch (LarkProviderException failure) {
            completeProviderFailure(observation, failure);
            throw failure;
        } catch (RuntimeException ignored) {
            observation.fail(OperationalTelemetry.ErrorCode.INVALID_RESPONSE);
            throw invalidResponse("LARK_TENANT_RESPONSE_INVALID");
        }
    }

    /** Performs only the fixed exact open_id member lookup. */
    public MemberResponse queryMember(
            LarkApiCallContext context, LarkOpenId exactOpenId) {
        OperationalTelemetry.Observation observation = observe(Operation.MEMBER_QUERY);
        LarkOpenId requested = Objects.requireNonNull(exactOpenId, "exactOpenId");
        String path = MEMBER_PREFIX + requested.value()
                + "?user_id_type=open_id&department_id_type=open_department_id";
        try (HttpResult result = authorized(context, Operation.MEMBER_QUERY, path, null)) {
            JsonNode user = successJson(result, false).path("data").path("user");
            LarkOpenId resolved = new LarkOpenId(requireText(user, "open_id", 123));
            if (!requested.equals(resolved)) {
                throw LarkProviderException.of(
                        LarkProviderErrorCode.IDENTITY_MISMATCH,
                        "Lark member identity does not match the exact lookup",
                        "LARK_MEMBER_IDENTITY_MISMATCH");
            }
            MemberResponse response = new MemberResponse(
                    resolved,
                    new LarkUnionId(requireText(user, "union_id", 123)),
                    MEMBER_PROVIDER_VERSION,
                    timeProvider.now());
            observation.succeed();
            return response;
        } catch (LarkProviderException failure) {
            completeProviderFailure(observation, failure);
            throw failure;
        } catch (RuntimeException ignored) {
            observation.fail(OperationalTelemetry.ErrorCode.INVALID_RESPONSE);
            throw invalidResponse("LARK_MEMBER_RESPONSE_INVALID");
        }
    }

    /** Sends one already validated fixed-template text with a stable Provider UUID. */
    MessageResponse sendTextMessage(
            LarkApiCallContext context, LarkTextMessageRequest request) {
        return sendTextMessage(
                request,
                (operation, path, body) -> authorized(context, operation, path, body));
    }

    /** Sends through one exact Notification Worker credential capability. */
    public MessageResponse sendTextMessage(
            LarkNotificationCredentialHandle credential, LarkTextMessageRequest request) {
        return sendTextMessage(
                request,
                (operation, path, body) -> authorized(credential, operation, path, body));
    }

    private MessageResponse sendTextMessage(
            LarkTextMessageRequest request, AuthorizedRequest authorizedRequest) {
        OperationalTelemetry.Observation observation = observe(Operation.MESSAGE_SEND);
        LarkTextMessageRequest value = Objects.requireNonNull(request, "request");
        byte[] payload = encode(Map.of(
                "receive_id", value.recipient().value(),
                "msg_type", "text",
                "content", encodeString(Map.of("text", value.text())),
                "uuid", value.idempotencyKey().toString()),
                "LARK_MESSAGE_REQUEST_ENCODING_FAILED");
        try (HttpResult result = authorizedRequest.execute(
                Operation.MESSAGE_SEND, MESSAGE_PATH, payload)) {
            JsonNode data = successJson(result, false).path("data");
            MessageResponse response = new MessageResponse(
                    new LarkMessageId(requireText(data, "message_id", 123)),
                    timeProvider.now());
            observation.succeed();
            return response;
        } catch (LarkProviderException failure) {
            completeProviderFailure(observation, failure);
            throw failure;
        } catch (RuntimeException ignored) {
            observation.fail(OperationalTelemetry.ErrorCode.INVALID_RESPONSE);
            throw invalidResponse("LARK_MESSAGE_RESPONSE_INVALID");
        } finally {
            Arrays.fill(payload, (byte) 0);
        }
    }

    /** Queries one exact message_id; existence does not imply that the recipient read it. */
    MessageResponse queryMessage(
            LarkApiCallContext context, LarkMessageId messageId) {
        return queryMessage(
                messageId,
                (operation, path, body) -> authorized(context, operation, path, body));
    }

    /** Queries through one exact Notification Worker credential capability. */
    public MessageResponse queryMessage(
            LarkNotificationCredentialHandle credential, LarkMessageId messageId) {
        return queryMessage(
                messageId,
                (operation, path, body) -> authorized(credential, operation, path, body));
    }

    private MessageResponse queryMessage(
            LarkMessageId messageId, AuthorizedRequest authorizedRequest) {
        OperationalTelemetry.Observation observation = observe(Operation.MESSAGE_QUERY);
        LarkMessageId requested = Objects.requireNonNull(messageId, "messageId");
        try (HttpResult result = authorizedRequest.execute(
                Operation.MESSAGE_QUERY, MESSAGE_PREFIX + requested.value(), null)) {
            JsonNode items = successJson(result, false).path("data").path("items");
            if (!items.isArray() || items.size() != 1) {
                throw invalidResponse("LARK_MESSAGE_QUERY_CARDINALITY_INVALID");
            }
            LarkMessageId resolved = new LarkMessageId(
                    requireText(items.path(0), "message_id", 123));
            if (!requested.equals(resolved)) {
                throw invalidResponse("LARK_MESSAGE_QUERY_IDENTITY_INVALID");
            }
            MessageResponse response = new MessageResponse(resolved, timeProvider.now());
            observation.succeed();
            return response;
        } catch (LarkProviderException failure) {
            completeProviderFailure(observation, failure);
            throw failure;
        } catch (RuntimeException ignored) {
            observation.fail(OperationalTelemetry.ErrorCode.INVALID_RESPONSE);
            throw invalidResponse("LARK_MESSAGE_QUERY_RESPONSE_INVALID");
        }
    }

    private OperationalTelemetry.Observation observe(Operation operation) {
        return telemetry.start(OperationalTelemetry.Request.lark(
                operation.possiblyWrites
                        ? OperationalTelemetry.Operation.DISPATCH
                        : OperationalTelemetry.Operation.QUERY));
    }

    private static void completeProviderFailure(
            OperationalTelemetry.Observation observation,
            LarkProviderException failure) {
        OperationalTelemetry.ErrorCode error = switch (failure.code()) {
            case AUTHENTICATION_REQUIRED -> OperationalTelemetry.ErrorCode.AUTHENTICATION;
            case PERMISSION_DENIED -> OperationalTelemetry.ErrorCode.PERMISSION;
            case RATE_LIMITED -> OperationalTelemetry.ErrorCode.RATE_LIMITED;
            case INVALID_RESPONSE -> OperationalTelemetry.ErrorCode.INVALID_RESPONSE;
            case IDENTITY_MISMATCH -> OperationalTelemetry.ErrorCode.IDENTITY_MISMATCH;
            case CREDENTIAL_UNAVAILABLE ->
                    OperationalTelemetry.ErrorCode.CREDENTIAL_UNAVAILABLE;
            case CANCELLED -> OperationalTelemetry.ErrorCode.CANCELLED;
            case PROVIDER_UNAVAILABLE, RESOURCE_UNAVAILABLE, CONNECTION_UNAVAILABLE ->
                    OperationalTelemetry.ErrorCode.UNAVAILABLE;
            case UNKNOWN_DELIVERY -> OperationalTelemetry.ErrorCode.UNKNOWN;
        };
        OperationalTelemetry.Outcome outcome = switch (failure.code()) {
            case RATE_LIMITED, PROVIDER_UNAVAILABLE, RESOURCE_UNAVAILABLE,
                    CONNECTION_UNAVAILABLE, CREDENTIAL_UNAVAILABLE ->
                    OperationalTelemetry.Outcome.RETRY;
            case CANCELLED -> OperationalTelemetry.Outcome.CANCELLED;
            case AUTHENTICATION_REQUIRED, PERMISSION_DENIED, IDENTITY_MISMATCH ->
                    OperationalTelemetry.Outcome.REJECTED;
            case INVALID_RESPONSE, UNKNOWN_DELIVERY -> OperationalTelemetry.Outcome.FAILURE;
        };
        observation.complete(outcome, error);
    }

    public String safeSummary() {
        return "LarkOpenApiClient[provider=lark, cachedTokens=" + tokenCache.size() + ']';
    }

    @Override
    public void close() {
        tokenCache.close();
    }

    private HttpResult authorized(
            LarkApiCallContext context,
            Operation operation,
            String pathAndQuery,
            byte[] body) {
        Objects.requireNonNull(context, "context");
        for (int authenticationAttempt = 0; authenticationAttempt < 2;
                authenticationAttempt++) {
            try (AuthorizedLarkAccess access = accessManager.authorize(
                    context, operation.purpose, operation.requiredCapabilities)) {
                LarkTenantToken token = tokenCache.getOrLoad(
                        access.cacheKey(), timeProvider.now(), () -> exchangeToken(access));
                HttpResult response = send(
                        request(pathAndQuery, body, token.value()), operation.possiblyWrites);
                if (response.status == 401) {
                    response.close();
                    tokenCache.invalidate(access.cacheKey());
                    if (authenticationAttempt == 0) {
                        continue;
                    }
                    throw LarkErrorNormalizer.normalize(response.status, response.headers);
                }
                if (response.status < 200 || response.status >= 300) {
                    try (response) {
                        throw LarkErrorNormalizer.normalize(response.status, response.headers);
                    }
                }
                return response;
            }
        }
        throw LarkProviderException.of(
                LarkProviderErrorCode.AUTHENTICATION_REQUIRED,
                "Lark authentication could not be refreshed",
                "LARK_AUTHENTICATION_REFRESH_FAILED");
    }

    private HttpResult authorized(
            LarkNotificationCredentialHandle credential,
            Operation operation,
            String pathAndQuery,
            byte[] body) {
        LarkNotificationCredentialHandle required = Objects.requireNonNull(
                credential, "credential");
        for (int authenticationAttempt = 0; authenticationAttempt < 2;
                authenticationAttempt++) {
            HttpResult response = required.withAuthorizedAccess(
                    operation.purpose,
                    operation.requiredCapabilities,
                    access -> {
                        LarkTenantToken token = tokenCache.getOrLoad(
                                access.cacheKey(), timeProvider.now(),
                                () -> exchangeToken(access));
                        HttpResult result = send(
                                request(pathAndQuery, body, token.value()),
                                operation.possiblyWrites);
                        if (result.status == 401) {
                            tokenCache.invalidate(access.cacheKey());
                        }
                        return result;
                    });
            if (response.status == 401) {
                response.close();
                if (authenticationAttempt == 0) {
                    continue;
                }
                throw LarkErrorNormalizer.normalize(response.status, response.headers);
            }
            if (response.status < 200 || response.status >= 300) {
                try (response) {
                    throw LarkErrorNormalizer.normalize(response.status, response.headers);
                }
            }
            return response;
        }
        throw LarkProviderException.of(
                LarkProviderErrorCode.AUTHENTICATION_REQUIRED,
                "Lark authentication could not be refreshed",
                "LARK_AUTHENTICATION_REFRESH_FAILED");
    }

    private LarkTenantToken exchangeToken(AuthorizedLarkAccess access) {
        return access.credentialHandle().useSecret(secret -> {
            LarkAppCredential appCredential = parseCredential(secret);
            byte[] payload = encode(Map.of(
                    "app_id", appCredential.appId,
                    "app_secret", appCredential.appSecret),
                    "LARK_TOKEN_REQUEST_ENCODING_FAILED");
            try (HttpResult result = send(
                    request(TOKEN_PATH, payload, null), false)) {
                if (result.status < 200 || result.status >= 300) {
                    throw LarkErrorNormalizer.normalize(result.status, result.headers);
                }
                JsonNode root = successJson(result, true);
                String token = requireText(root, "tenant_access_token", 4_096);
                long expiresIn = root.path("expire").asLong(-1);
                if (expiresIn < 120 || expiresIn > 86_400) {
                    throw invalidResponse("LARK_TOKEN_EXPIRY_INVALID");
                }
                return new LarkTenantToken(
                        token,
                        UtcTimestamp.from(timeProvider.now().value().plusSeconds(expiresIn)));
            } finally {
                Arrays.fill(payload, (byte) 0);
            }
        });
    }

    private HttpRequest request(String pathAndQuery, byte[] body, String tenantToken) {
        URI target = resolve(pathAndQuery);
        HttpRequest.Builder builder = HttpRequest.newBuilder(target)
                .timeout(requestTimeout)
                .header("Accept", "application/json");
        if (tenantToken != null) {
            builder.header("Authorization", "Bearer " + tenantToken);
        }
        if (body == null) {
            return builder.GET().build();
        }
        return builder.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
    }

    private HttpResult send(HttpRequest request, boolean possiblyWrites) {
        try {
            HttpResponse<InputStream> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());
            if (!request.uri().equals(response.uri())) {
                response.body().close();
                throw LarkProviderException.of(
                        LarkProviderErrorCode.PROVIDER_UNAVAILABLE,
                        "Lark redirects are not allowed",
                        "LARK_REDIRECT_REJECTED");
            }
            try (InputStream input = response.body()) {
                byte[] bytes = input.readNBytes(maximumResponseBytes + 1);
                if (bytes.length > maximumResponseBytes) {
                    Arrays.fill(bytes, (byte) 0);
                    throw invalidResponse("LARK_RESPONSE_TOO_LARGE");
                }
                return new HttpResult(response.statusCode(), response.headers(), bytes);
            }
        } catch (LarkProviderException failure) {
            throw failure;
        } catch (HttpTimeoutException ignored) {
            throw LarkErrorNormalizer.transportFailure(possiblyWrites);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            throw LarkErrorNormalizer.cancelled();
        } catch (IOException | IllegalArgumentException ignored) {
            throw LarkErrorNormalizer.transportFailure(possiblyWrites);
        }
    }

    private JsonNode successJson(HttpResult result, boolean credentialExchange) {
        JsonNode root;
        try {
            root = objectMapper.readTree(result.body);
        } catch (RuntimeException ignored) {
            throw invalidResponse("LARK_RESPONSE_JSON_INVALID");
        }
        if (root == null || root.path("code").asInt(-1) != 0) {
            throw LarkProviderException.of(
                    credentialExchange
                            ? LarkProviderErrorCode.AUTHENTICATION_REQUIRED
                            : LarkProviderErrorCode.INVALID_RESPONSE,
                    credentialExchange
                            ? "Lark application credential was not accepted"
                            : "Lark response contains a non-success Provider code",
                    credentialExchange
                            ? "LARK_TOKEN_PROVIDER_REJECTED"
                            : "LARK_PROVIDER_CODE_REJECTED");
        }
        return root;
    }

    private LarkAppCredential parseCredential(byte[] secret) {
        if (secret.length < 2 || secret.length > MAX_CREDENTIAL_BYTES) {
            throw LarkProviderException.of(
                    LarkProviderErrorCode.CREDENTIAL_UNAVAILABLE,
                    "Lark application credential has an invalid size",
                    "LARK_CREDENTIAL_SHAPE_INVALID");
        }
        try {
            JsonNode root = objectMapper.readTree(secret);
            return new LarkAppCredential(
                    requireText(root, "app_id", 200),
                    requireText(root, "app_secret", 1_000));
        } catch (LarkProviderException failure) {
            throw failure;
        } catch (RuntimeException ignored) {
            throw LarkProviderException.of(
                    LarkProviderErrorCode.CREDENTIAL_UNAVAILABLE,
                    "Lark application credential has an invalid shape",
                    "LARK_CREDENTIAL_SHAPE_INVALID");
        }
    }

    private byte[] encode(Map<String, ?> value, String evidenceCode) {
        try {
            return objectMapper.writeValueAsBytes(value);
        } catch (RuntimeException ignored) {
            throw LarkProviderException.of(
                    LarkProviderErrorCode.INVALID_RESPONSE,
                    "Lark fixed request could not be encoded",
                    evidenceCode);
        }
    }

    private String encodeString(Map<String, ?> value) {
        byte[] bytes = encode(value, "LARK_MESSAGE_CONTENT_ENCODING_FAILED");
        try {
            return new String(bytes, StandardCharsets.UTF_8);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private URI resolve(String pathAndQuery) {
        Assert.state(pathAndQuery != null && pathAndQuery.startsWith("/")
                        && !pathAndQuery.startsWith("//"),
                "Lark fixed operation path is invalid");
        URI target = baseUri.resolve(pathAndQuery);
        if (!Objects.equals(baseUri.getScheme(), target.getScheme())
                || !Objects.equals(baseUri.getHost(), target.getHost())
                || baseUri.getPort() != target.getPort()) {
            throw new IllegalStateException("Lark fixed operation escaped its configured origin");
        }
        return target;
    }

    private static String requireText(JsonNode node, String field, int maximumLength) {
        JsonNode value = Objects.requireNonNull(node, "node").path(field);
        if (!value.isString() || value.stringValue().isBlank()
                || value.stringValue().length() > maximumLength
                || value.stringValue().codePoints().anyMatch(Character::isISOControl)) {
            throw invalidResponse("LARK_REQUIRED_FIELD_INVALID");
        }
        return value.stringValue();
    }

    private static Duration requireTimeout(Duration value) {
        Duration required = Objects.requireNonNull(value, "requestTimeout");
        if (required.compareTo(Duration.ofMillis(100)) < 0
                || required.compareTo(Duration.ofMinutes(2)) > 0) {
            throw new IllegalArgumentException("Lark request timeout must be within [100ms, 2m]");
        }
        return required;
    }

    private static LarkProviderException invalidResponse(String evidenceCode) {
        return LarkProviderException.of(
                LarkProviderErrorCode.INVALID_RESPONSE,
                "Lark response failed bounded structural validation",
                evidenceCode);
    }

    public record TenantResponse(LarkTenantKey tenantKey, UtcTimestamp observedAt) {
        public TenantResponse {
            tenantKey = Objects.requireNonNull(tenantKey, "tenantKey");
            observedAt = Objects.requireNonNull(observedAt, "observedAt");
        }

        @Override
        public String toString() {
            return "TenantResponse[identity=REDACTED]";
        }
    }

    public record MemberResponse(
            LarkOpenId openId,
            LarkUnionId unionId,
            LarkProviderVersion providerVersion,
            UtcTimestamp observedAt) {
        public MemberResponse {
            openId = Objects.requireNonNull(openId, "openId");
            unionId = Objects.requireNonNull(unionId, "unionId");
            providerVersion = Objects.requireNonNull(providerVersion, "providerVersion");
            observedAt = Objects.requireNonNull(observedAt, "observedAt");
        }

        @Override
        public String toString() {
            return "MemberResponse[identity=REDACTED]";
        }
    }

    public record MessageResponse(LarkMessageId messageId, UtcTimestamp observedAt) {
        public MessageResponse {
            messageId = Objects.requireNonNull(messageId, "messageId");
            observedAt = Objects.requireNonNull(observedAt, "observedAt");
        }

        @Override
        public String toString() {
            return "MessageResponse[identity=REDACTED]";
        }
    }

    private enum Operation {
        TENANT_QUERY("lark.tenant.query", false, Optional.empty()),
        MEMBER_QUERY(
                "lark.member.query-exact", false,
                Optional.of(LarkCollaborationCapabilities.MEMBER_MAPPING)),
        MESSAGE_SEND(
                "lark.message.send-fixed-text", true,
                Optional.of(LarkCollaborationCapabilities.NOTIFICATION_DELIVERY)),
        MESSAGE_QUERY(
                "lark.message.query-exact", false,
                Optional.of(LarkCollaborationCapabilities.NOTIFICATION_DELIVERY));

        private final String purpose;
        private final boolean possiblyWrites;
        private final Optional<ProviderCapabilities> requiredCapabilities;

        Operation(
                String purpose,
                boolean possiblyWrites,
                Optional<ProviderCapabilities> requiredCapabilities) {
            this.purpose = purpose;
            this.possiblyWrites = possiblyWrites;
            this.requiredCapabilities = requiredCapabilities;
        }
    }

    private record LarkAppCredential(String appId, String appSecret) {
        private LarkAppCredential {
            if (appId == null || appId.isBlank() || appSecret == null || appSecret.isBlank()) {
                throw new IllegalArgumentException("Lark application credential is incomplete");
            }
        }

        @Override
        public String toString() {
            return "LarkAppCredential[REDACTED]";
        }
    }

    @FunctionalInterface
    private interface AuthorizedRequest {
        HttpResult execute(Operation operation, String pathAndQuery, byte[] body);
    }

    private static final class HttpResult implements AutoCloseable {
        private final int status;
        private final java.net.http.HttpHeaders headers;
        private byte[] body;

        private HttpResult(int status, java.net.http.HttpHeaders headers, byte[] body) {
            this.status = status;
            this.headers = Objects.requireNonNull(headers, "headers");
            this.body = Objects.requireNonNull(body, "body");
        }

        @Override
        public void close() {
            if (body != null) {
                Arrays.fill(body, (byte) 0);
                body = null;
            }
        }
    }
}
