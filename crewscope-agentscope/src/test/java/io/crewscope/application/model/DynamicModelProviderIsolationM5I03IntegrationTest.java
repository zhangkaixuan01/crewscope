package io.crewscope.application.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.Model;
import io.crewscope.agentscope.model.AgentScopeFormatterPolicy;
import io.crewscope.agentscope.model.AgentScopeModelAdapterRegistry;
import io.crewscope.agentscope.model.AgentScopeModelFactory;
import io.crewscope.agentscope.model.OpenAiAgentScopeModelProviderAdapter;
import io.crewscope.agentscope.model.OpenAiCompatibleAgentScopeModelProviderAdapter;
import io.crewscope.agentscope.model.StructuredOutputCompatibility;
import io.crewscope.agentscope.model.TrustedModelBuildRequest;
import io.crewscope.application.credential.CredentialDescriptor;
import io.crewscope.application.credential.CredentialSecret;
import io.crewscope.application.credential.ResolvedCredential;
import io.crewscope.domain.agent.AgentConfigurationHash;
import io.crewscope.domain.agent.AgentReasoningMode;
import io.crewscope.domain.agent.ResolvedModelRole;
import io.crewscope.domain.agent.SafeModelGenerateOptions;
import io.crewscope.domain.model.ModelAdapterKey;
import io.crewscope.domain.model.ModelCatalogCoordinate;
import io.crewscope.domain.model.ModelCatalogEntryId;
import io.crewscope.domain.model.ModelCatalogRevision;
import io.crewscope.domain.model.ModelConnectionId;
import io.crewscope.domain.model.ModelCredentialVersion;
import io.crewscope.domain.model.ModelEndpoint;
import io.crewscope.domain.model.ModelId;
import io.crewscope.domain.model.ModelProviderKey;
import io.crewscope.domain.model.ModelRegistryHash;
import io.crewscope.domain.model.ModelRevision;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/** Real AgentScope HTTP evidence that dynamic provider state stays connection-scoped. */
class DynamicModelProviderIsolationM5I03IntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-08-23T00:00:00Z");
    private static final ModelRegistryHash HASH = new ModelRegistryHash("3".repeat(64));
    private static final AgentConfigurationHash CONFIGURATION_HASH =
            new AgentConfigurationHash("4".repeat(64));

    @Test
    void concurrentDeepSeekAndOpenAiCallsNeverCrossEndpointCredentialModelOrFormatter() throws Exception {
        try (ProviderStub deepSeek = ProviderStub.start(); ProviderStub openAi = ProviderStub.start()) {
            Duration timeout = Duration.ofSeconds(5);
            Duration firstBackoff = Duration.ofMillis(1);
            Duration maximumBackoff = Duration.ofMillis(2);
            AgentScopeModelFactory factory = new AgentScopeModelFactory(
                    new AgentScopeModelAdapterRegistry(List.of(
                            new OpenAiCompatibleAgentScopeModelProviderAdapter(
                                    timeout, firstBackoff, maximumBackoff),
                            new OpenAiAgentScopeModelProviderAdapter(
                                    timeout, firstBackoff, maximumBackoff))),
                    Duration.ofMinutes(1),
                    16,
                    timeout,
                    firstBackoff,
                    maximumBackoff);
            TrustedModelBuildRequest deepSeekRequest = request(
                    "deepseek",
                    "openai-compatible",
                    "deepseek-model",
                    deepSeek,
                    AgentScopeFormatterPolicy.DEEPSEEK,
                    StructuredOutputCompatibility.SYNTHETIC_TOOL,
                    ResolvedModelRole.PRIMARY);
            TrustedModelBuildRequest openAiRequest = request(
                    "openai",
                    "openai",
                    "openai-model",
                    openAi,
                    AgentScopeFormatterPolicy.OPENAI,
                    StructuredOutputCompatibility.NATIVE,
                    ResolvedModelRole.FALLBACK);
            Model deepSeekModel = factory.build(
                    deepSeekRequest, handle(deepSeekRequest, "deepseek-key"));
            Model openAiModel = factory.build(
                    openAiRequest, handle(openAiRequest, "openai-key"));

            CompletableFuture<?>[] calls = new CompletableFuture<?>[4];
            for (int index = 0; index < calls.length; index++) {
                Model selected = index % 2 == 0 ? deepSeekModel : openAiModel;
                calls[index] = CompletableFuture.runAsync(() -> selected
                        .stream(List.of(new UserMessage("isolation probe")), List.of(), null)
                        .collectList()
                        .block(Duration.ofSeconds(20)));
            }
            CompletableFuture.allOf(calls).join();

            assertEquals(2, deepSeek.requests.size());
            assertEquals(2, openAi.requests.size());
            assertRequests(deepSeek.requests, "deepseek-key", "deepseek-model", "openai-model");
            assertRequests(openAi.requests, "openai-key", "openai-model", "deepseek-model");
            assertFalse(deepSeekModel.supportsNativeStructuredOutput());
            assertFalse(deepSeekModel.supportsNativeStructuredOutputWithTools());
            assertTrue(openAiModel.supportsNativeStructuredOutput());
            assertTrue(openAiModel.supportsNativeStructuredOutputWithTools());
        }
    }

    private static void assertRequests(
            List<CapturedRequest> requests,
            String credential,
            String model,
            String forbiddenModel) throws Exception {
        for (CapturedRequest request : requests) {
            assertEquals("Bearer " + credential, request.authorization());
            JsonNode payload = JSON.readTree(request.body());
            assertEquals(model, payload.path("model").asText());
            assertFalse(request.body().contains(forbiddenModel));
        }
    }

    private static TrustedModelBuildRequest request(
            String providerName,
            String adapterName,
            String modelName,
            ProviderStub provider,
            AgentScopeFormatterPolicy formatter,
            StructuredOutputCompatibility structured,
            ResolvedModelRole role) {
        ModelProviderKey providerKey = new ModelProviderKey(providerName);
        return new TrustedModelBuildRequest(
                OrganizationId.generate(),
                role,
                providerKey,
                HASH,
                new ModelAdapterKey(adapterName),
                ModelConnectionId.generate(),
                1,
                new ModelCredentialVersion(1),
                new ModelEndpoint(provider.baseUrl()),
                "/v1/chat/completions",
                new ModelCatalogCoordinate(
                        ModelCatalogEntryId.generate(),
                        providerKey,
                        new ModelId(modelName),
                        new ModelCatalogRevision(1)),
                HASH,
                new ModelRevision("2026-08"),
                formatter,
                structured,
                new SafeModelGenerateOptions(
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(256L),
                        AgentReasoningMode.DEFAULT,
                        true,
                        false,
                        Optional.empty(),
                        1),
                CONFIGURATION_HASH);
    }

    private static ProviderCredentialHandle handle(
            TrustedModelBuildRequest request, String credential) {
        return new ProviderCredentialHandle(
                request.connectionId(),
                request.credentialVersion(),
                UtcTimestamp.from(NOW),
                Duration.ofMinutes(1),
                TimeProvider.from(Clock.fixed(NOW, ZoneOffset.UTC)),
                (ignoredConnection, ignoredVersion) -> new ResolvedCredential(
                        mock(CredentialDescriptor.class), CredentialSecret.utf8(credential)));
    }

    private record CapturedRequest(String authorization, String body) {}

    private static final class ProviderStub implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor;
        private final List<CapturedRequest> requests = new CopyOnWriteArrayList<>();

        private ProviderStub(HttpServer server, ExecutorService executor) {
            this.server = server;
            this.executor = executor;
        }

        static ProviderStub start() throws IOException {
            HttpServer server = HttpServer.create(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            ExecutorService executor = Executors.newFixedThreadPool(12);
            server.setExecutor(executor);
            ProviderStub result = new ProviderStub(server, executor);
            server.createContext("/v1/chat/completions", result::handle);
            server.start();
            return result;
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private void handle(HttpExchange exchange) throws IOException {
            String request = new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new CapturedRequest(
                    exchange.getRequestHeaders().getFirst("Authorization"), request));
            String response = "data: {\"id\":\"m5-i03\",\"object\":\"chat.completion.chunk\","
                    + "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\","
                    + "\"content\":\"ok\"},\"finish_reason\":null}]}\n\n"
                    + "data: {\"id\":\"m5-i03\",\"object\":\"chat.completion.chunk\","
                    + "\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                    + "data: [DONE]\n\n";
            byte[] payload = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
