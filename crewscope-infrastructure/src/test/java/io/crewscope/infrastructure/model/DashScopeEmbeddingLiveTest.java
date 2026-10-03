package io.crewscope.infrastructure.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.embedding.EmbeddingClient;
import io.crewscope.application.model.ProviderCredentialHandle;
import io.crewscope.application.model.ProviderCredentialOperation;
import io.crewscope.domain.model.ModelAdapterKey;
import io.crewscope.domain.model.ModelBillingSubject;
import io.crewscope.domain.model.ModelConnection;
import io.crewscope.domain.model.ModelConnectionId;
import io.crewscope.domain.model.ModelConnectionOwner;
import io.crewscope.domain.model.ModelCredentialBinding;
import io.crewscope.domain.model.ModelCredentialSubject;
import io.crewscope.domain.model.ModelCredentialVersion;
import io.crewscope.domain.model.ModelDataPolicy;
import io.crewscope.domain.model.ModelDataRetentionMode;
import io.crewscope.domain.model.ModelEndpoint;
import io.crewscope.domain.model.ModelProviderDefinition;
import io.crewscope.domain.model.ModelProviderKey;
import io.crewscope.domain.model.ModelRegion;
import io.crewscope.domain.model.ModelTrainingUsagePolicy;
import io.crewscope.domain.shared.id.CredentialId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Optional live smoke against the real DashScope compatible-mode endpoint (S01 §3.2).
 * Runs only when {@code S01B_DASHSCOPE_KEY_FILE} points at the key file whose first
 * line is {@code apikey:<value>}. The key is read into memory, consumed by the handle
 * callback and zeroed — it never reaches assertions, logs or failure messages.
 */
@EnabledIfEnvironmentVariable(named = "S01B_DASHSCOPE_KEY_FILE", matches = ".+")
class DashScopeEmbeddingLiveTest {

    private static final String ENDPOINT = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private static final int DIMENSIONS = 1024;

    @Test
    void deliversAMeasuredDimensionVectorFromTextEmbeddingV4() throws Exception {
        String keyLine = Files.readAllLines(Path.of(System.getenv("S01B_DASHSCOPE_KEY_FILE")))
                .stream()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("key file is empty"));
        String apiKey = keyLine.startsWith("apikey:")
                ? keyLine.substring("apikey:".length()).strip()
                : keyLine;

        OpenAiCompatibleEmbeddingClient client = new OpenAiCompatibleEmbeddingClient(
                Duration.ofSeconds(5), Duration.ofSeconds(30), 2, Duration.ofSeconds(1));

        EmbeddingClient.EmbeddingCall call = client.embed(
                connection(), new EmbeddingClient.EmbeddingRequest(
                        "text-embedding-v4",
                        DIMENSIONS,
                        List.of("crewscope embedding capability probe")),
                handle(apiKey));

        assertTrue(call.delivered(), () -> "delivery failed with " + call.failureCode());
        assertEquals(1, call.vectors().size());
        assertEquals(DIMENSIONS, call.vectors().get(0).length);
        for (float component : call.vectors().get(0)) {
            assertTrue(Float.isFinite(component));
        }
        assertTrue(call.attempts().get(call.attempts().size() - 1).usage().totalTokens() > 0,
                "the measured usage echo must be booked, never assumed free");
    }

    private static ModelConnection connection() {
        OrganizationId organizationId = OrganizationId.generate();
        PrincipalId actor = PrincipalId.generate();
        ModelRegion region = new ModelRegion("cn");
        UtcTimestamp now = UtcTimestamp.parse("2026-10-02T08:00:00Z");
        ModelProviderDefinition provider = ModelProviderDefinition.publish(
                new ModelProviderKey("dashscope"),
                "DashScope",
                new ModelAdapterKey("openai-compatible"),
                new ModelEndpoint(ENDPOINT),
                Set.of(region),
                new ModelDataPolicy(
                        ModelDataRetentionMode.PROVIDER_MANAGED,
                        Optional.empty(),
                        ModelTrainingUsagePolicy.PROHIBITED),
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
    private static ProviderCredentialHandle handle(String apiKey) {
        ProviderCredentialHandle handle = mock(ProviderCredentialHandle.class);
        when(handle.useSecret(any())).thenAnswer(invocation -> {
            ProviderCredentialOperation<Object> operation = invocation.getArgument(0);
            byte[] bytes = apiKey.getBytes(StandardCharsets.UTF_8);
            try {
                return operation.apply(bytes);
            } finally {
                java.util.Arrays.fill(bytes, (byte) 0);
            }
        });
        return handle;
    }
}
