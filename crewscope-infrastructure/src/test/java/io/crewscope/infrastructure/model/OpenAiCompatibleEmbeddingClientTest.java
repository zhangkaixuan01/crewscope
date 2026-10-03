package io.crewscope.infrastructure.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.crewscope.application.embedding.EmbeddingClient;
import io.crewscope.application.model.ProviderCredentialHandle;
import io.crewscope.application.model.ProviderCredentialOperation;
import io.crewscope.domain.model.ModelAdapterKey;
import io.crewscope.domain.model.ModelBillingSubject;
import io.crewscope.domain.model.ModelConnection;
import io.crewscope.domain.model.ModelConnectionHealthFailureCode;
import io.crewscope.domain.model.ModelConnectionId;
import io.crewscope.domain.model.ModelConnectionOwner;
import io.crewscope.domain.model.ModelCredentialBinding;
import io.crewscope.domain.model.ModelCredentialSubject;
import io.crewscope.domain.model.ModelCredentialVersion;
import io.crewscope.domain.model.ModelDataPolicy;
import io.crewscope.domain.model.ModelEndpoint;
import io.crewscope.domain.model.ModelProviderDefinition;
import io.crewscope.domain.model.ModelProviderKey;
import io.crewscope.domain.model.ModelRegion;
import io.crewscope.domain.model.ModelTokenUsage;
import io.crewscope.domain.shared.id.CredentialId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Loopback HTTP proof for the M10-I01a embeddings transport: the frozen wire shape,
 * the bounded 429/5xx retry with {@code Retry-After} precedence, sanitized failure
 * mapping and the invariant that the bearer secret never crosses back out.
 */
class OpenAiCompatibleEmbeddingClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SECRET = "loopback-embedding-secret";

    private HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsTheFrozenWireShapeAndDeliversOrderedVectors() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        startServer((exchange, hit) -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            method.set(exchange.getRequestMethod());
            path.set(exchange.getRequestURI().getPath());
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            send(exchange, 200, """
                    {"data":[{"index":0,"embedding":[0.1,0.25]},{"index":1,"embedding":[0.5,0.75]}],
                     "usage":{"prompt_tokens":11,"total_tokens":11}}
                    """);
        });

        EmbeddingClient.EmbeddingCall call = client(3, Duration.ofMillis(5)).embed(
                connection(), request(2, "alpha", "beta"), handle());

        assertTrue(call.delivered());
        assertEquals(2, call.vectors().size());
        assertEquals(0.1f, call.vectors().get(0)[0]);
        assertEquals(0.25f, call.vectors().get(0)[1]);
        assertEquals(0.75f, call.vectors().get(1)[1]);
        assertEquals(1, call.attempts().size());
        assertTrue(call.attempts().get(0).delivered());
        assertEquals(new ModelTokenUsage(11, 0, 0, 11), call.attempts().get(0).usage());

        assertEquals("POST", method.get());
        assertEquals("/v1/embeddings", path.get());
        assertEquals("Bearer " + SECRET, authorization.get());
        assertTrue(contentType.get().startsWith("application/json"));
        JsonNode sent = JSON.readTree(body.get());
        assertEquals("text-embedding-v4", sent.get("model").asText());
        assertEquals(2, sent.get("dimensions").asInt());
        assertEquals(2, sent.get("input").size());
        assertEquals("alpha", sent.get("input").get(0).asText());
        assertEquals("beta", sent.get("input").get(1).asText());
    }

    @Test
    void placesReturnedVectorsByTheirIndexField() throws Exception {
        startServer((exchange, hit) -> send(exchange, 200, """
                {"data":[{"index":1,"embedding":[0.5,0.75]},{"index":0,"embedding":[0.1,0.25]}],
                 "usage":{"prompt_tokens":4,"total_tokens":4}}
                """));

        EmbeddingClient.EmbeddingCall call = client(3, Duration.ofMillis(5)).embed(
                connection(), request(2, "alpha", "beta"), handle());

        assertTrue(call.delivered());
        assertEquals(0.1f, call.vectors().get(0)[0]);
        assertEquals(0.5f, call.vectors().get(1)[0]);
    }

    @Test
    void mapsAuthenticationFailureWithoutRetrying() throws Exception {
        startServer((exchange, hit) -> send(exchange, 401, "{\"error\":\"bad key\"}"));

        EmbeddingClient.EmbeddingCall call = client(3, Duration.ofMillis(5)).embed(
                connection(), request(2, "alpha"), handle());

        assertFalse(call.delivered());
        assertEquals(Optional.of(ModelConnectionHealthFailureCode.AUTHENTICATION_FAILED), call.failureCode());
        assertEquals(1, call.attempts().size(), "401 is terminal — no second HTTP round trip");
        assertEquals(1, hits.get());
    }

    @Test
    void retriesRateLimitedResponsesUntilSuccess() throws Exception {
        List<Integer> statuses = List.of(429, 429, 200);
        startServer((exchange, hit) -> {
            int status = statuses.get(Math.min(hit - 1, statuses.size() - 1));
            if (status == 429) {
                exchange.getResponseHeaders().add("Retry-After", "0");
            }
            send(exchange, status, status == 200
                    ? "{\"data\":[{\"index\":0,\"embedding\":[0.5,0.5]}],\"usage\":{\"prompt_tokens\":3,\"total_tokens\":3}}"
                    : "{\"error\":\"slow down\"}");
        });

        EmbeddingClient.EmbeddingCall call = client(3, Duration.ofMillis(5)).embed(
                connection(), request(2, "alpha"), handle());

        assertTrue(call.delivered());
        assertEquals(3, hits.get());
        assertEquals(3, call.attempts().size(), "every real HTTP attempt is booked");
        assertFalse(call.attempts().get(0).delivered());
        assertFalse(call.attempts().get(1).delivered());
        assertTrue(call.attempts().get(2).delivered());
        assertEquals(3, call.attempts().get(2).attempt());
    }

    @Test
    void honoursRetryAfterZeroOverTheExponentialBaseDelay() throws Exception {
        startServer((exchange, hit) -> {
            if (hit < 2) {
                exchange.getResponseHeaders().add("Retry-After", "0");
                send(exchange, 429, "{}");
                return;
            }
            send(exchange, 200, "{\"data\":[{\"index\":0,\"embedding\":[0.5,0.5]}]}");
        });
        // A 30 s base delay proves precedence: without the Retry-After override the
        // second attempt would sleep the full base delay and blow past the budget.
        OpenAiCompatibleEmbeddingClient client = client(3, Duration.ofSeconds(30));

        long startedAt = System.nanoTime();
        EmbeddingClient.EmbeddingCall call = client.embed(connection(), request(2, "alpha"), handle());
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        assertTrue(call.delivered());
        assertTrue(elapsedMillis < 10_000, "Retry-After: 0 must override the 30 s base delay");
    }

    @Test
    void mapsProviderServerErrorsAndExhaustsTheAttemptBudget() throws Exception {
        startServer((exchange, hit) -> send(exchange, 500, "{\"error\":\"boom\"}"));

        EmbeddingClient.EmbeddingCall call = client(3, Duration.ofMillis(5)).embed(
                connection(), request(2, "alpha"), handle());

        assertFalse(call.delivered());
        assertEquals(Optional.of(ModelConnectionHealthFailureCode.PROVIDER_REJECTED), call.failureCode());
        assertEquals(3, call.attempts().size());
        assertEquals(3, hits.get());
    }

    @Test
    void mapsATimeoutToTheSanitizedTimeoutCode() throws Exception {
        startServer((exchange, hit) -> {
            try {
                Thread.sleep(3_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            send(exchange, 200, "{}");
        });
        OpenAiCompatibleEmbeddingClient client = new OpenAiCompatibleEmbeddingClient(
                Duration.ofSeconds(1), Duration.ofSeconds(1), 3, Duration.ofMillis(5));

        EmbeddingClient.EmbeddingCall call = client.embed(connection(), request(2, "alpha"), handle());

        assertFalse(call.delivered());
        assertEquals(Optional.of(ModelConnectionHealthFailureCode.TIMEOUT), call.failureCode());
        assertEquals(1, call.attempts().size());
    }

    @Test
    void rejectsDeliveredVectorsOfTheWrongDimension() throws Exception {
        startServer((exchange, hit) -> send(exchange, 200,
                "{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2,0.3]}]}"));

        EmbeddingClient.EmbeddingCall call = client(3, Duration.ofMillis(5)).embed(
                connection(), request(2, "alpha"), handle());

        assertFalse(call.delivered());
        assertEquals(Optional.of(ModelConnectionHealthFailureCode.PROVIDER_REJECTED), call.failureCode());
        assertTrue(call.vectors().isEmpty());
    }

    @Test
    void rejectsNonFiniteVectorComponents() throws Exception {
        startServer((exchange, hit) -> send(exchange, 200,
                "{\"data\":[{\"index\":0,\"embedding\":[1e999,0.5]}]}"));

        EmbeddingClient.EmbeddingCall call = client(3, Duration.ofMillis(5)).embed(
                connection(), request(2, "alpha"), handle());

        assertFalse(call.delivered());
        assertEquals(Optional.of(ModelConnectionHealthFailureCode.PROVIDER_REJECTED), call.failureCode());
    }

    @Test
    void reportsUnreportedUsageWhenTheEchoIsMissing() throws Exception {
        startServer((exchange, hit) -> send(exchange, 200,
                "{\"data\":[{\"index\":0,\"embedding\":[0.5,0.5]}]}"));

        EmbeddingClient.EmbeddingCall call = client(3, Duration.ofMillis(5)).embed(
                connection(), request(2, "alpha"), handle());

        assertTrue(call.delivered());
        assertEquals(ModelTokenUsage.unreported(), call.attempts().get(0).usage());
    }

    @Test
    void doesNotFollowRedirectsWithTheBearerCredential() throws Exception {
        AtomicReference<String> redirectedAuthorization = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            exchange.getResponseHeaders().add("Location", "/leak");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/leak", exchange -> {
            redirectedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();

        EmbeddingClient.EmbeddingCall call = client(3, Duration.ofMillis(5)).embed(
                connection(), request(2, "alpha"), handle());

        assertFalse(call.delivered());
        assertEquals(Optional.of(ModelConnectionHealthFailureCode.PROVIDER_REJECTED), call.failureCode());
        assertNull(redirectedAuthorization.get(), "the bearer credential must never leave the host");
    }

    @Test
    void mapsARefusedOrUnreachableEndpointToASanitizedTransportCode() throws Exception {
        // Reserve a port, then close it: the connection attempt must fail sanitized. The
        // JDK reports a refused connect on some platforms as HttpConnectTimeoutException,
        // so both transport-dead codes are acceptable — never a crash, never a retry.
        HttpServer dead = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = dead.getAddress().getPort();
        dead.stop(0);
        ModelConnection connection = connection("http://127.0.0.1:" + port + "/v1");

        EmbeddingClient.EmbeddingCall call = client(3, Duration.ofMillis(5)).embed(
                connection, request(2, "alpha"), handle());

        assertFalse(call.delivered());
        ModelConnectionHealthFailureCode code = call.failureCode().orElseThrow();
        assertTrue(
                code == ModelConnectionHealthFailureCode.ENDPOINT_UNREACHABLE
                        || code == ModelConnectionHealthFailureCode.TIMEOUT,
                "a dead transport maps to a sanitized code, got " + code);
        assertEquals(1, call.attempts().size());
    }

    @Test
    void keepsTheBearerSecretOutOfResults() throws Exception {
        startServer((exchange, hit) -> send(exchange, 500, "{\"error\":\"no " + SECRET + " here\"}"));

        EmbeddingClient.EmbeddingCall call = client(2, Duration.ofMillis(5)).embed(
                connection(), request(2, "alpha"), handle());

        String rendered = call.toString() + call.attempts().toString() + call.failureCode();
        assertFalse(rendered.contains(SECRET), "the bearer secret must never cross back out");
    }

    @Test
    void rejectsInvalidConstructorTuning() {
        assertThrows(IllegalArgumentException.class,
                () -> new OpenAiCompatibleEmbeddingClient(
                        Duration.ofSeconds(1), Duration.ofMillis(500), 3, Duration.ofMillis(5)));
        assertThrows(IllegalArgumentException.class,
                () -> new OpenAiCompatibleEmbeddingClient(
                        Duration.ofSeconds(1), Duration.ofSeconds(2), 0, Duration.ofMillis(5)));
        assertThrows(IllegalArgumentException.class,
                () -> new OpenAiCompatibleEmbeddingClient(
                        Duration.ofSeconds(1), Duration.ofSeconds(2), 6, Duration.ofMillis(5)));
        assertThrows(IllegalArgumentException.class,
                () -> new OpenAiCompatibleEmbeddingClient(
                        Duration.ZERO, Duration.ofSeconds(2), 3, Duration.ofMillis(5)));
    }

    // ------------------------------------------------------------------ harness

    private interface Responder {
        void respond(HttpExchange exchange, int hit) throws IOException;
    }

    private void startServer(Responder responder) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            int hit = hits.incrementAndGet();
            responder.respond(exchange, hit);
        });
        server.start();
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
    }

    private static OpenAiCompatibleEmbeddingClient client(int maxAttempts, Duration retryBaseDelay) {
        return new OpenAiCompatibleEmbeddingClient(
                Duration.ofSeconds(1), Duration.ofSeconds(2), maxAttempts, retryBaseDelay);
    }

    private static EmbeddingClient.EmbeddingRequest request(int dimensions, String... input) {
        return new EmbeddingClient.EmbeddingRequest(
                "text-embedding-v4", dimensions, List.of(input));
    }

    private ModelConnection connection() {
        return connection("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
    }

    private static ModelConnection connection(String endpoint) {
        OrganizationId organizationId = OrganizationId.generate();
        PrincipalId actor = PrincipalId.generate();
        ModelRegion region = new ModelRegion("cn");
        UtcTimestamp now = UtcTimestamp.parse("2026-10-02T08:00:00Z");
        ModelProviderDefinition provider = ModelProviderDefinition.publish(
                new ModelProviderKey("dashscope"),
                "DashScope",
                new ModelAdapterKey("openai-compatible"),
                new ModelEndpoint(endpoint),
                Set.of(region),
                new ModelDataPolicy(
                        io.crewscope.domain.model.ModelDataRetentionMode.PROVIDER_MANAGED,
                        Optional.empty(),
                        io.crewscope.domain.model.ModelTrainingUsagePolicy.PROHIBITED),
                actor,
                now);
        return ModelConnection.open(
                provider,
                ModelConnectionId.generate(),
                ModelConnectionOwner.organization(organizationId),
                provider.defaultEndpoint(),
                region,
                new ModelCredentialBinding(
                        CredentialId.generate(),
                        ModelCredentialSubject.organization(organizationId),
                        new ModelCredentialVersion(0)),
                ModelBillingSubject.organization(organizationId),
                actor,
                now);
    }

    @SuppressWarnings("unchecked")
    private static ProviderCredentialHandle handle() {
        ProviderCredentialHandle handle = mock(ProviderCredentialHandle.class);
        when(handle.useSecret(any())).thenAnswer(invocation -> {
            ProviderCredentialOperation<Object> operation = invocation.getArgument(0);
            byte[] bytes = SECRET.getBytes(StandardCharsets.UTF_8);
            try {
                return operation.apply(bytes);
            } finally {
                java.util.Arrays.fill(bytes, (byte) 0);
            }
        });
        return handle;
    }
}
