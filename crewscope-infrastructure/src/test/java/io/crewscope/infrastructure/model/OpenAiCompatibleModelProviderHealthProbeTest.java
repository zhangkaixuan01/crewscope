package io.crewscope.infrastructure.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import io.crewscope.application.model.ModelProviderHealthProbe;
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
import io.crewscope.domain.shared.id.CredentialId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Loopback HTTP proof for status mapping and bearer injection without response retention. */
class OpenAiCompatibleModelProviderHealthProbeTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsBearerOnlyToModelsEndpointAndMapsSuccess() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        startServer(200, authorization, path);
        Fixture fixture = fixture();

        ModelProviderHealthProbe.ProbeResult result = probe().probe(
                fixture.provider(), fixture.connection(), handle("loopback-secret"));

        assertEquals(ModelProviderHealthProbe.ProbeResult.success(), result);
        assertEquals("Bearer loopback-secret", authorization.get());
        assertEquals("/v1/models", path.get());
        assertFalse(result.toString().contains("loopback-secret"));
    }

    @Test
    void mapsAuthenticationAndRateLimitResponsesToStableCodes() throws Exception {
        startServer(401, new AtomicReference<>(), new AtomicReference<>());
        Fixture fixture = fixture();
        assertEquals(
                Optional.of(ModelConnectionHealthFailureCode.AUTHENTICATION_FAILED),
                probe().probe(fixture.provider(), fixture.connection(), handle("bad-secret"))
                        .failureCode());
        server.stop(0);

        startServer(429, new AtomicReference<>(), new AtomicReference<>());
        fixture = fixture();
        assertEquals(
                Optional.of(ModelConnectionHealthFailureCode.RATE_LIMITED),
                probe().probe(fixture.provider(), fixture.connection(), handle("limited-secret"))
                        .failureCode());
    }

    private void startServer(
            int status,
            AtomicReference<String> authorization,
            AtomicReference<String> path) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            path.set(exchange.getRequestURI().getPath());
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
    }

    private OpenAiCompatibleModelProviderHealthProbe probe() {
        return new OpenAiCompatibleModelProviderHealthProbe(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(),
                Duration.ofSeconds(2));
    }

    @SuppressWarnings("unchecked")
    private static ProviderCredentialHandle handle(String secret) {
        ProviderCredentialHandle handle = mock(ProviderCredentialHandle.class);
        when(handle.useSecret(any())).thenAnswer(invocation -> {
            ProviderCredentialOperation<Object> operation = invocation.getArgument(0);
            byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
            try {
                return operation.apply(bytes);
            } finally {
                java.util.Arrays.fill(bytes, (byte) 0);
            }
        });
        return handle;
    }

    private Fixture fixture() {
        OrganizationId organizationId = OrganizationId.generate();
        PrincipalId actor = PrincipalId.generate();
        ModelRegion region = new ModelRegion("global");
        UtcTimestamp now = UtcTimestamp.parse("2026-08-23T08:00:00Z");
        ModelProviderDefinition provider = ModelProviderDefinition.publish(
                new ModelProviderKey("loopback"),
                "Loopback",
                new ModelAdapterKey("openai-compatible"),
                new ModelEndpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"),
                Set.of(region),
                ModelDataPolicy.noRetention(),
                actor,
                now);
        ModelConnection connection = ModelConnection.open(
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
        return new Fixture(provider, connection);
    }

    private record Fixture(ModelProviderDefinition provider, ModelConnection connection) {}
}
