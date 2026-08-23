package io.crewscope.application.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.crewscope.agentscope.model.AgentScopeFormatterPolicy;
import io.crewscope.agentscope.model.AgentScopeModelAdapterRegistry;
import io.crewscope.agentscope.model.AgentScopeModelBuildException;
import io.crewscope.agentscope.model.AgentScopeModelFactory;
import io.crewscope.agentscope.model.AgentScopeModelProviderAdapter;
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
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import reactor.core.publisher.Flux;

class AgentScopeModelFactoryTest {

    private static final Instant NOW = Instant.parse("2026-08-23T00:00:00Z");
    private static final ModelRegistryHash REGISTRY_HASH =
            new ModelRegistryHash("1".repeat(64));
    private static final AgentConfigurationHash CONFIGURATION_HASH =
            new AgentConfigurationHash("2".repeat(64));

    @org.junit.jupiter.api.Test
    void failsFastForDuplicateAdapterKeys() {
        CapturingAdapter first = new CapturingAdapter(new ModelAdapterKey("openai-compatible"));
        CapturingAdapter second = new CapturingAdapter(new ModelAdapterKey("openai-compatible"));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> new AgentScopeModelAdapterRegistry(List.of(first, second)));

        assertTrue(failure.getMessage().contains("Duplicate model adapter key"));
    }

    @org.junit.jupiter.api.Test
    void rejectsAnInvertedRetryBackoffRangeOutsideSpringConfiguration() {
        CapturingAdapter adapter = new CapturingAdapter(new ModelAdapterKey("openai-compatible"));

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new AgentScopeModelFactory(
                        new AgentScopeModelAdapterRegistry(List.of(adapter)),
                        Duration.ofMinutes(5),
                        8,
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(1)));

        assertTrue(failure.getMessage().contains("retryMaximumBackoff"));
    }

    @org.junit.jupiter.api.Test
    void cachesOnlyTheExactVersionedCoordinateAndConsumesEveryHandle() {
        CapturingAdapter adapter = new CapturingAdapter(new ModelAdapterKey("openai-compatible"));
        AgentScopeModelFactory factory = factory(adapter);
        TrustedModelBuildRequest firstRequest = request(4, new ModelCredentialVersion(2), options(1));
        ProviderCredentialHandle firstHandle = handle(firstRequest, "first-secret");

        Model first = factory.build(firstRequest, firstHandle);
        ProviderCredentialHandle cacheHitHandle = handle(firstRequest, "first-secret");
        Model cacheHit = factory.build(firstRequest, cacheHitHandle);
        TrustedModelBuildRequest changed = request(5, new ModelCredentialVersion(2), options(1));
        Model afterConnectionChange = factory.build(changed, handle(changed, "first-secret"));

        assertSame(first, cacheHit);
        assertNotSame(first, afterConnectionChange);
        assertEquals(2, adapter.builds.get());
        assertEquals(2, factory.cacheSize());
        assertTrue(firstHandle.isClosed());
        assertTrue(cacheHitHandle.isClosed());
    }

    @org.junit.jupiter.api.Test
    void rejectsCredentialCoordinateMismatchAndUnknownAdapterWithoutLeakingHandle() {
        CapturingAdapter adapter = new CapturingAdapter(new ModelAdapterKey("openai-compatible"));
        AgentScopeModelFactory factory = factory(adapter);
        TrustedModelBuildRequest request = request(4, new ModelCredentialVersion(2), options(1));
        TrustedModelBuildRequest otherVersion = request(4, new ModelCredentialVersion(3), options(1));
        ProviderCredentialHandle mismatched = handle(otherVersion, "secret");

        AgentScopeModelBuildException mismatch = assertThrows(
                AgentScopeModelBuildException.class, () -> factory.build(request, mismatched));
        assertEquals(
                AgentScopeModelBuildException.Code.CREDENTIAL_COORDINATE_MISMATCH,
                mismatch.code());
        assertTrue(mismatched.isClosed());

        TrustedModelBuildRequest unknown = withAdapter(request, new ModelAdapterKey("missing"));
        ProviderCredentialHandle unknownHandle = handle(unknown, "secret");
        AgentScopeModelBuildException missing = assertThrows(
                AgentScopeModelBuildException.class, () -> factory.build(unknown, unknownHandle));
        assertEquals(AgentScopeModelBuildException.Code.UNKNOWN_ADAPTER, missing.code());
        assertTrue(unknownHandle.isClosed());
    }

    @org.junit.jupiter.api.Test
    void freezesConnectionAndSafeGenerationOptionsAgainstRequestOverrides() {
        CapturingAdapter adapter = new CapturingAdapter(new ModelAdapterKey("openai-compatible"));
        AgentScopeModelFactory factory = factory(adapter);
        TrustedModelBuildRequest request = request(4, new ModelCredentialVersion(2), options(2));
        Model model = factory.build(request, handle(request, "trusted-secret"));

        GenerateOptions hostile = GenerateOptions.builder()
                .apiKey("attacker")
                .baseUrl("https://attacker.invalid")
                .endpointPath("/steal")
                .modelName("attacker-model")
                .temperature(1.9)
                .maxTokens(9_999)
                .additionalHeaders(java.util.Map.of("X-Attack", "true"))
                .additionalQueryParams(java.util.Map.of("leak", "true"))
                .additionalBodyParams(java.util.Map.of("leak", true))
                .build();
        model.stream(List.<Msg>of(), List.<ToolSchema>of(), hostile).blockLast();

        GenerateOptions received = adapter.model.lastOptions;
        assertEquals(new BigDecimal("0.2").doubleValue(), received.getTemperature());
        assertEquals(512, received.getMaxTokens());
        assertEquals(2, received.getExecutionConfig().getMaxAttempts());
        assertEquals("trusted-secret", adapter.lastCredential);
        assertFalse(received.getAdditionalHeaders().containsKey("X-Attack"));
        assertFalse(received.getAdditionalQueryParams().containsKey("leak"));
        assertFalse(received.getAdditionalBodyParams().containsKey("leak"));
        assertEquals(null, received.getApiKey());
        assertEquals(null, received.getBaseUrl());
        assertEquals(null, received.getModelName());
    }

    @org.junit.jupiter.api.Test
    void sanitizesProviderFailuresAndPropagatesCancellationWithoutObservationContext() {
        CapturingAdapter failingAdapter =
                new CapturingAdapter(new ModelAdapterKey("openai-compatible"));
        failingAdapter.model.failure = new IllegalStateException("provider secret payload");
        TrustedModelBuildRequest failingRequest = request(1, new ModelCredentialVersion(1), options(1));
        Model failing = factory(failingAdapter).build(
                failingRequest, handle(failingRequest, "credential-never-in-error"));

        RuntimeException safeFailure = assertThrows(
                RuntimeException.class,
                () -> failing.stream(List.of(), List.of(), null).blockLast());
        assertEquals("SafeModelExecutionException", safeFailure.getClass().getSimpleName());
        assertFalse(safeFailure.getMessage().contains("provider secret payload"));
        assertFalse(safeFailure.getMessage().contains("credential-never-in-error"));

        CapturingAdapter cancelAdapter =
                new CapturingAdapter(new ModelAdapterKey("openai-compatible"));
        cancelAdapter.model.neverComplete = true;
        TrustedModelBuildRequest cancelRequest = request(1, new ModelCredentialVersion(1), options(1));
        Model cancellable = factory(cancelAdapter).build(
                cancelRequest, handle(cancelRequest, "cancel-secret"));
        reactor.core.Disposable subscription =
                cancellable.stream(List.of(), List.of(), null).subscribe();
        subscription.dispose();

        assertTrue(cancelAdapter.model.canceled.get());
    }

    private static AgentScopeModelFactory factory(CapturingAdapter adapter) {
        return new AgentScopeModelFactory(
                new AgentScopeModelAdapterRegistry(List.of(adapter)),
                Duration.ofMinutes(5),
                8,
                Duration.ofSeconds(30),
                Duration.ofMillis(1),
                Duration.ofMillis(5));
    }

    private static ProviderCredentialHandle handle(
            TrustedModelBuildRequest request, String secret) {
        TimeProvider time = TimeProvider.from(Clock.fixed(NOW, ZoneOffset.UTC));
        return new ProviderCredentialHandle(
                request.connectionId(),
                request.credentialVersion(),
                UtcTimestamp.from(NOW),
                Duration.ofMinutes(1),
                time,
                (ignoredConnection, ignoredVersion) -> new ResolvedCredential(
                        mock(CredentialDescriptor.class), CredentialSecret.utf8(secret)));
    }

    private static TrustedModelBuildRequest request(
            long connectionVersion,
            ModelCredentialVersion credentialVersion,
            SafeModelGenerateOptions options) {
        ModelProviderKey provider = new ModelProviderKey("deepseek");
        return new TrustedModelBuildRequest(
                OrganizationId.generate(),
                ResolvedModelRole.PRIMARY,
                provider,
                REGISTRY_HASH,
                new ModelAdapterKey("openai-compatible"),
                ModelConnectionId.generate(),
                connectionVersion,
                credentialVersion,
                new ModelEndpoint("https://api.deepseek.com"),
                "/v1/chat/completions",
                new ModelCatalogCoordinate(
                        ModelCatalogEntryId.generate(),
                        provider,
                        new ModelId("deepseek-v4-flash"),
                        new ModelCatalogRevision(1)),
                REGISTRY_HASH,
                new ModelRevision("2026-08"),
                AgentScopeFormatterPolicy.DEEPSEEK,
                StructuredOutputCompatibility.SYNTHETIC_TOOL,
                options,
                CONFIGURATION_HASH);
    }

    private static TrustedModelBuildRequest withAdapter(
            TrustedModelBuildRequest request, ModelAdapterKey adapterKey) {
        return new TrustedModelBuildRequest(
                request.organizationId(), request.role(), request.providerKey(),
                request.providerDefinitionHash(), adapterKey, request.connectionId(),
                request.connectionVersion(), request.credentialVersion(), request.endpoint(),
                request.endpointPath(), request.catalogCoordinate(), request.catalogContentHash(),
                request.modelRevision(), request.formatterPolicy(),
                request.structuredOutputCompatibility(), request.generateOptions(),
                request.compatibilityHash());
    }

    private static SafeModelGenerateOptions options(int attempts) {
        return new SafeModelGenerateOptions(
                Optional.of(new BigDecimal("0.2")),
                Optional.of(new BigDecimal("0.9")),
                Optional.of(512L),
                AgentReasoningMode.DEFAULT,
                true,
                false,
                Optional.of(7L),
                attempts);
    }

    private static final class CapturingAdapter implements AgentScopeModelProviderAdapter {
        private final ModelAdapterKey key;
        private final AtomicInteger builds = new AtomicInteger();
        private final CapturingModel model = new CapturingModel();
        private String lastCredential;

        private CapturingAdapter(ModelAdapterKey key) {
            this.key = key;
        }

        @Override
        public ModelAdapterKey adapterKey() {
            return key;
        }

        @Override
        public String adapterVersion() {
            return "test-v1";
        }

        @Override
        public Model build(
                TrustedModelBuildRequest request, ProviderCredentialHandle credentialHandle) {
            builds.incrementAndGet();
            return credentialHandle.useSecret(secret -> {
                lastCredential = new String(secret, java.nio.charset.StandardCharsets.UTF_8);
                return model;
            });
        }
    }

    private static final class CapturingModel implements Model {
        private GenerateOptions lastOptions;
        private RuntimeException failure;
        private boolean neverComplete;
        private final AtomicBoolean canceled = new AtomicBoolean();

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            lastOptions = options;
            if (failure != null) {
                return Flux.error(failure);
            }
            if (neverComplete) {
                return Flux.<ChatResponse>never().doOnCancel(() -> canceled.set(true));
            }
            return Flux.empty();
        }

        @Override
        public String getModelName() {
            return "safe-model";
        }
    }
}
