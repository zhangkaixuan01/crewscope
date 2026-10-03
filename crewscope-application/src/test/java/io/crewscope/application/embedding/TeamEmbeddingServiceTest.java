package io.crewscope.application.embedding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.event.PendingOutboxEvent;
import io.crewscope.application.model.EmbeddingCapabilityProbe;
import io.crewscope.application.model.ModelCatalogEntryRepository;
import io.crewscope.application.model.ModelConnectionCredentialService;
import io.crewscope.application.model.ModelConnectionRepository;
import io.crewscope.application.model.ModelPriceScheduleRepository;
import io.crewscope.application.model.ModelProviderDefinitionRepository;
import io.crewscope.application.model.ModelProviderHealthProbe;
import io.crewscope.application.model.OpenProviderCredentialHandleRequest;
import io.crewscope.application.model.ProviderCredentialHandle;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.model.ModelAdapterKey;
import io.crewscope.domain.model.ModelCapability;
import io.crewscope.domain.model.ModelCatalogCoordinate;
import io.crewscope.domain.model.ModelCatalogEntry;
import io.crewscope.domain.model.ModelCatalogEntryId;
import io.crewscope.domain.model.ModelCatalogRevision;
import io.crewscope.domain.model.ModelConnection;
import io.crewscope.domain.model.ModelConnectionHealthFailureCode;
import io.crewscope.domain.model.ModelConnectionId;
import io.crewscope.domain.model.ModelConnectionOwner;
import io.crewscope.domain.model.ModelCredentialBinding;
import io.crewscope.domain.model.ModelCredentialSubject;
import io.crewscope.domain.model.ModelCredentialVersion;
import io.crewscope.domain.model.ModelBillingSubject;
import io.crewscope.domain.model.ModelDataPolicy;
import io.crewscope.domain.model.ModelDataRetentionMode;
import io.crewscope.domain.model.ModelEndpoint;
import io.crewscope.domain.model.ModelId;
import io.crewscope.domain.model.ModelPriceRevision;
import io.crewscope.domain.model.ModelPriceSource;
import io.crewscope.domain.model.ModelProviderDefinition;
import io.crewscope.domain.model.ModelProviderKey;
import io.crewscope.domain.model.ModelRegion;
import io.crewscope.domain.model.ModelRegistryStatus;
import io.crewscope.domain.model.ModelRevision;
import io.crewscope.domain.model.ModelTokenPrice;
import io.crewscope.domain.model.ModelTokenUsage;
import io.crewscope.domain.model.ModelTrainingUsagePolicy;
import io.crewscope.domain.model.ModelUsageFactId;
import io.crewscope.domain.model.ModelUsageRole;
import io.crewscope.domain.model.event.ModelUsageFactRecorded;
import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.id.CredentialId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.retrieval.EmbeddingModelRevision;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.team.UninitializedTeam;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Application-face contract for I01a: the governance-chain resolution (TEAM before
 * ORGANIZATION, ACTIVE provider/catalog, non-training policy, effective price), the
 * pre-HTTP batch validation, per-attempt usage facts including failed attempts, the
 * deterministic call identities and the capability probe legs.
 */
final class TeamEmbeddingServiceTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-10-02T08:00:00Z");
    private static final UtcTimestamp PRICE_EFFECTIVE_FROM =
            UtcTimestamp.parse("2026-10-01T00:00:00Z");
    private static final ModelProviderKey DASHSCOPE = new ModelProviderKey("dashscope");
    private static final ModelProviderKey TRAINING_PROVIDER =
            new ModelProviderKey("training-provider");
    private static final ModelRegion CHINA = new ModelRegion("cn");
    private static final UUID CORRELATION_ID = UUID.randomUUID();

    private final OrganizationId organizationId = OrganizationId.generate();
    private final PrincipalId actor = PrincipalId.generate();
    private final io.crewscope.domain.identity.Principal actorPrincipal =
            io.crewscope.domain.identity.Principal.create(
                    actor,
                    io.crewscope.domain.identity.PrincipalScope.organization(organizationId),
                    io.crewscope.domain.identity.PrincipalType.USER,
                    Optional.empty(),
                    "Owner",
                    Optional.empty(),
                    io.crewscope.domain.identity.PrincipalVisibility.ORGANIZATION,
                    NOW);
    private final TeamInitialization initialization =
            TeamInitialization.create(actorPrincipal, "Platform", NOW);
    private final TeamId teamId = initialization.team().id();

    private final FakeTeamRepository teams = new FakeTeamRepository();
    private final FakeConnectionRepository connections = new FakeConnectionRepository();
    private final FakeProviderRepository providers = new FakeProviderRepository();
    private final FakeCatalogRepository catalogs = new FakeCatalogRepository();
    private final FakePriceRepository prices = new FakePriceRepository();
    private final FakeCredentialStore credentials = new FakeCredentialStore();
    private final Store store = new Store();
    private final FakeEmbeddingClient client = new FakeEmbeddingClient();

    private final ModelConnectionCredentialService credentialService =
            new ModelConnectionCredentialService(
                    connections,
                    providers,
                    credentials,
                    (provider, connection, handle) ->
                            ModelProviderHealthProbe.ProbeResult.success(),
                    store,
                    store,
                    new DirectTransactionExecutor(),
                    () -> NOW,
                    Duration.ofMinutes(5));

    private final TeamEmbeddingService service = new TeamEmbeddingService(
            teams, connections, providers, catalogs, prices,
            credentialService, client, store, store,
            new DirectTransactionExecutor(), () -> NOW);

    private final ModelProviderDefinition dashscopeProvider = ModelProviderDefinition.publish(
            DASHSCOPE,
            "DashScope",
            new ModelAdapterKey("openai-compatible"),
            new ModelEndpoint("https://dashscope.aliyuncs.com/compatible-mode/v1"),
            java.util.Set.of(CHINA),
            new ModelDataPolicy(
                    ModelDataRetentionMode.PROVIDER_MANAGED,
                    Optional.empty(),
                    ModelTrainingUsagePolicy.PROHIBITED),
            actor,
            NOW);

    @BeforeEach
    void setUp() {
        providers.register(dashscopeProvider);
        ModelCatalogEntry entry = ModelCatalogEntry.publishInitial(
                dashscopeProvider,
                ModelCatalogEntryId.generate(),
                new ModelId("text-embedding-v4"),
                new ModelRevision("text-embedding-v4"),
                "DashScope text-embedding-v4",
                33_000,
                1,
                java.util.Set.of(new ModelCapability("embedding")),
                java.util.Set.of(CHINA),
                actor,
                NOW);
        catalogs.append(entry);
        prices.append(ModelPriceRevision.publish(
                entry.coordinate(),
                1,
                PRICE_EFFECTIVE_FROM,
                new ModelTokenPrice(
                        new BigDecimal("0.5"), new BigDecimal("0"), Optional.empty(), "CNY"),
                new ModelPriceSource("https://help.aliyun.com/zh/model-studio/embeddings"),
                actor,
                NOW));
        registerConnection(
                dashscopeProvider,
                ModelConnectionOwner.team(initialization.team()),
                ModelCredentialSubject.team(organizationId, teamId),
                ModelBillingSubject.team(organizationId, teamId),
                CredentialSubject.team(organizationId, teamId));
    }

    // ------------------------------------------------------------------ happy path

    @Test
    void embedsThroughTheResolvedConnectionAndRecordsOneUsageFactPerAttempt() {
        client.attempts = List.of(new EmbeddingClient.Attempt(
                1, true, new ModelTokenUsage(11, 0, 0, 11)));

        EmbeddingBatchResult result = service.embed(command(UUID.randomUUID(), List.of("alpha", "beta")));

        assertEquals(2, result.vectors().size());
        assertEquals(1024, result.vectors().get(0).length);
        assertEquals("text-embedding-v4", result.model().modelKey());
        assertEquals(1024, result.model().dimension());
        assertEquals(1, result.model().revision());
        assertEquals(DASHSCOPE, result.coordinate().providerKey());

        assertEquals(1, client.connections.size());
        ModelConnection used = client.connections.get(0);
        assertEquals(used.id(), result.connectionId());
        assertEquals(used.version(), result.connectionVersion());
        assertEquals("text-embedding-v4", client.requests.get(0).model());
        assertEquals(1024, client.requests.get(0).dimensions());
        assertEquals(List.of("alpha", "beta"), client.requests.get(0).input());

        ModelUsageFactRecorded usage = usagePayload(0);
        assertEquals(ModelUsageRole.EMBEDDING, usage.role());
        assertEquals(DASHSCOPE, usage.providerKey());
        assertEquals(new ModelId("text-embedding-v4"), usage.modelId());
        assertEquals(used.id(), usage.connectionId());
        assertEquals(used.version(), usage.connectionVersion());
        assertEquals(new ModelTokenUsage(11, 0, 0, 11), usage.usage());
        assertEquals(Optional.of(teamId), usageEvents().get(0).teamId());
        assertEquals(1, usageOutboxCount());
    }

    @Test
    void prefersTheTeamOwnedConnectionAndFallsBackToTheOrganizationOwnedOne() {
        ModelConnection orgConnection = registerConnection(
                dashscopeProvider,
                ModelConnectionOwner.organization(organizationId),
                ModelCredentialSubject.organization(organizationId),
                ModelBillingSubject.organization(organizationId),
                CredentialSubject.organization(organizationId));

        EmbeddingBatchResult teamScoped = service.embed(command(UUID.randomUUID(), List.of("x")));
        assertTrue(
                !teamScoped.connectionId().equals(orgConnection.id()),
                "the TEAM-owned connection must win while it is usable");

        // Retire the TEAM connection; the ORGANIZATION fallback must take over.
        connections.values.remove(teamScoped.connectionId());
        EmbeddingBatchResult orgScoped = service.embed(command(UUID.randomUUID(), List.of("x")));
        assertEquals(orgConnection.id(), orgScoped.connectionId());
    }

    @Test
    void skipsSuspendedConnectionsWhileResolving() {
        connections.values.entrySet().stream()
                .filter(entry -> entry.getValue().owner().teamId().isPresent())
                .forEach(entry -> connections.values.put(
                        entry.getKey(),
                        entry.getValue().suspend(
                                entry.getValue().version(), actor, NOW)));
        // The suspended TEAM connection leaves no usable candidate.
        assertThrows(
                DomainValidationException.class,
                () -> service.embed(command(UUID.randomUUID(), List.of("x"))));
    }

    @Test
    void rejectsWhenNoUsableConnectionExists() {
        connections.values.clear();

        DomainValidationException denied = assertThrows(
                DomainValidationException.class,
                () -> service.embed(command(UUID.randomUUID(), List.of("x"))));

        assertEquals("teamEmbedding.connection", denied.error().details().get("field"));
        assertTrue(client.connections.isEmpty(), "no HTTP call may leave the resolution failure");
    }

    @Test
    void rejectsAnEmbeddingEntryWithoutAnEffectivePrice() {
        prices.values.clear();

        DomainValidationException denied = assertThrows(
                DomainValidationException.class,
                () -> service.embed(command(UUID.randomUUID(), List.of("x"))));

        assertEquals("teamEmbedding.price", denied.error().details().get("field"));
        assertTrue(
                denied.error().details().get("reason").contains("PRICE_UNAVAILABLE"),
                "the operator-facing reason must carry PRICE_UNAVAILABLE");
    }

    @Test
    void neverSendsTeamContentToAProviderAllowedToTrainOnIt() {
        ModelProviderDefinition trainingAllowed = ModelProviderDefinition.publish(
                TRAINING_PROVIDER,
                "Training Provider",
                new ModelAdapterKey("openai-compatible"),
                new ModelEndpoint("https://training.example.com/v1"),
                java.util.Set.of(CHINA),
                new ModelDataPolicy(
                        ModelDataRetentionMode.PROVIDER_MANAGED,
                        Optional.empty(),
                        ModelTrainingUsagePolicy.PROVIDER_DEFAULT),
                actor,
                NOW);
        providers.register(trainingAllowed);
        ModelCatalogEntry trainingEntry = ModelCatalogEntry.publishInitial(
                trainingAllowed,
                ModelCatalogEntryId.generate(),
                new ModelId("training-embedding"),
                new ModelRevision("training-embedding"),
                "Training Provider Embedding",
                33_000,
                1,
                java.util.Set.of(new ModelCapability("embedding")),
                java.util.Set.of(CHINA),
                actor,
                NOW);
        catalogs.append(trainingEntry);
        prices.append(ModelPriceRevision.publish(
                trainingEntry.coordinate(),
                1,
                PRICE_EFFECTIVE_FROM,
                new ModelTokenPrice(
                        new BigDecimal("0.4"), new BigDecimal("0"), Optional.empty(), "CNY"),
                new ModelPriceSource("https://training.example.com/pricing"),
                actor,
                NOW));
        // The training-allowed provider owns the only TEAM connection; the compliant
        // provider only has the ORGANIZATION fallback.
        registerConnection(
                dashscopeProvider,
                ModelConnectionOwner.organization(organizationId),
                ModelCredentialSubject.organization(organizationId),
                ModelBillingSubject.organization(organizationId),
                CredentialSubject.organization(organizationId));
        connections.values.entrySet().removeIf(
                entry -> entry.getValue().owner().teamId().isPresent());
        registerConnection(
                trainingAllowed,
                ModelConnectionOwner.team(initialization.team()),
                ModelCredentialSubject.team(organizationId, teamId),
                ModelBillingSubject.team(organizationId, teamId),
                CredentialSubject.team(organizationId, teamId));
        ModelConnection orgFallback = connections.values.values().stream()
                .filter(value -> value.owner().teamId().isEmpty())
                .findFirst().orElseThrow();

        EmbeddingBatchResult result = service.embed(command(UUID.randomUUID(), List.of("x")));

        assertEquals(orgFallback.id(), result.connectionId());
        assertTrue(
                client.connections.stream().noneMatch(
                        connection -> connection.providerKey().equals(TRAINING_PROVIDER)),
                "the non-compliant provider must never receive content");
    }

    // ------------------------------------------------------------------ pre-validation

    @Test
    void prevalidatesBatchShapeBeforeSpendingAnHttpRoundTrip() {
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < EmbeddingClient.MAX_BATCH + 1; i++) {
            tooMany.add("item-" + i);
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> service.embed(command(UUID.randomUUID(), tooMany)));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.embed(command(UUID.randomUUID(), List.of("ok", ""))));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.embed(command(
                        UUID.randomUUID(),
                        List.of("a".repeat(EmbeddingClient.MAX_INPUT_CHARS + 1)))));
        assertTrue(
                client.connections.isEmpty(),
                "malformed batches must be rejected before any provider call");
        assertTrue(store.events.isEmpty(), "no usage facts exist without a real attempt");
    }

    // ------------------------------------------------------------------ delivery failures

    @Test
    void recordsUsageFactsForFailedAttemptsBeforeFailingDelivery() {
        client.succeed = false;
        client.failureCode = ModelConnectionHealthFailureCode.RATE_LIMITED;
        client.attempts = List.of(
                new EmbeddingClient.Attempt(1, false, new ModelTokenUsage(4, 0, 0, 4)),
                new EmbeddingClient.Attempt(2, false, new ModelTokenUsage(4, 0, 0, 4)));

        EmbeddingDeliveryException denied = assertThrows(
                EmbeddingDeliveryException.class,
                () -> service.embed(command(UUID.randomUUID(), List.of("x"))));

        assertEquals(ModelConnectionHealthFailureCode.RATE_LIMITED, denied.failureCode());
        assertEquals(2, usageEvents().size());
        assertEquals(2, usageOutboxCount());
        assertEquals(1, usagePayload(0).attempt());
        assertEquals(new ModelTokenUsage(4, 0, 0, 4), usagePayload(0).usage());
        assertEquals(2, usagePayload(1).attempt());
    }

    @Test
    void rejectsDeliveredVectorsOfTheWrongDimensionOrWithNonFiniteComponents() {
        client.vectorDimension = 512;
        assertThrows(
                EmbeddingDeliveryException.class,
                () -> service.embed(command(UUID.randomUUID(), List.of("x"))));
        assertEquals(1, usageEvents().size(), "tokens spent on a violated delivery are kept");

        client.vectorDimension = 1024;
        client.nonFinite = true;
        assertThrows(
                EmbeddingDeliveryException.class,
                () -> service.embed(command(UUID.randomUUID(), List.of("x"))));
        assertEquals(2, usageEvents().size());
    }

    @Test
    void replaysOntoTheSameDeterministicCallIds() {
        UUID commandId = UUID.randomUUID();

        service.embed(command(commandId, List.of("x")));
        service.embed(command(commandId, List.of("x")));

        ModelUsageFactId first = usagePayload(0).callId();
        ModelUsageFactId second = usagePayload(1).callId();
        assertEquals(first, second);
        assertEquals(
                usageEvents().get(0).eventId(),
                usageEvents().get(1).eventId(),
                "the event identity must be equally deterministic for deduplicated replay");
    }

    // ------------------------------------------------------------------ capability probe

    @Test
    void probeIsNotApplicableToAProviderWithoutAnEmbeddingEntry() {
        ModelProviderDefinition chatOnly = ModelProviderDefinition.publish(
                new ModelProviderKey("chat-only"),
                "Chat Only",
                new ModelAdapterKey("openai-compatible"),
                new ModelEndpoint("https://chat.example.com"),
                java.util.Set.of(CHINA),
                ModelDataPolicy.noRetention(),
                actor,
                NOW);
        providers.register(chatOnly);
        ModelConnection connection = registerConnection(
                chatOnly,
                ModelConnectionOwner.team(initialization.team()),
                ModelCredentialSubject.team(organizationId, teamId),
                ModelBillingSubject.team(organizationId, teamId),
                CredentialSubject.team(organizationId, teamId));

        Optional<EmbeddingCapabilityProbe.Outcome> outcome;
        try (ProviderCredentialHandle handle = openHandle(connection)) {
            outcome = service.probeCapability(chatOnly, connection, handle, CORRELATION_ID);
        }

        assertTrue(outcome.isEmpty());
        assertTrue(client.connections.isEmpty(), "not applicable means not one HTTP call");
    }

    @Test
    void probeDeliversAHealthyOutcomeAndBooksItsUsageWithTheConnectionIdentity() {
        client.attempts = List.of(new EmbeddingClient.Attempt(
                1, true, new ModelTokenUsage(7, 0, 0, 7)));
        ModelConnection orgConnection = registerConnection(
                dashscopeProvider,
                ModelConnectionOwner.organization(organizationId),
                ModelCredentialSubject.organization(organizationId),
                ModelBillingSubject.organization(organizationId),
                CredentialSubject.organization(organizationId));

        Optional<EmbeddingCapabilityProbe.Outcome> first;
        Optional<EmbeddingCapabilityProbe.Outcome> second;
        try (ProviderCredentialHandle handle = openHandle(orgConnection)) {
            first = service.probeCapability(dashscopeProvider, orgConnection, handle, CORRELATION_ID);
            second = service.probeCapability(dashscopeProvider, orgConnection, handle, CORRELATION_ID);
        }

        assertTrue(first.orElseThrow().healthy());
        assertTrue(second.orElseThrow().healthy());
        assertEquals(1, client.requests.get(0).input().size());
        assertEquals(List.of(EmbeddingCapabilityProbe.PROBE_INPUT), client.requests.get(0).input());

        assertEquals(2, usageEvents().size());
        ModelUsageFactRecorded usage = usagePayload(0);
        assertEquals(orgConnection.id(), usage.connectionId());
        assertEquals(Optional.empty(), usageEvents().get(0).teamId(),
                "an ORGANIZATION-owned connection books without a Team scope");
        assertEquals(usage.callId(), usagePayload(1).callId(),
                "re-verifying the same credential version replays the same call identity");
        assertEquals(
                io.crewscope.domain.shared.event.EventActorType.SERVICE,
                usageEvents().get(0).actor().type());
    }

    @Test
    void probeReportsTheSanitizedFailureCodeAndKeepsTheUsageFact() {
        client.succeed = false;
        client.failureCode = ModelConnectionHealthFailureCode.AUTHENTICATION_FAILED;
        ModelConnection connection = teamConnection();

        Optional<EmbeddingCapabilityProbe.Outcome> outcome;
        try (ProviderCredentialHandle handle = openHandle(connection)) {
            outcome = service.probeCapability(
                    dashscopeProvider, connection, handle, CORRELATION_ID);
        }

        assertEquals(
                ModelConnectionHealthFailureCode.AUTHENTICATION_FAILED,
                outcome.orElseThrow().failureCode().orElseThrow());
        assertEquals(1, usageEvents().size());
    }

    // ------------------------------------------------------------------ helpers

    private TeamEmbeddingCommand command(UUID commandId, List<String> inputs) {
        return new TeamEmbeddingCommand(
                organizationId, teamId, actor, commandId, CORRELATION_ID, inputs);
    }

    private ModelConnection teamConnection() {
        return connections.values.values().stream()
                .filter(value -> value.owner().teamId().isPresent())
                .findFirst()
                .orElseThrow();
    }

    private ProviderCredentialHandle openHandle(ModelConnection connection) {
        return credentialService.openHandle(new OpenProviderCredentialHandleRequest(
                organizationId,
                connection.id(),
                connection.version(),
                connection.credentialBinding().credentialVersion(),
                actor,
                "model:test",
                UUID.randomUUID()));
    }

    private ModelConnection registerConnection(
            ModelProviderDefinition provider,
            ModelConnectionOwner owner,
            ModelCredentialSubject bindingSubject,
            ModelBillingSubject billingSubject,
            CredentialSubject storeSubject) {
        ModelConnectionId connectionId = ModelConnectionId.generate();
        CredentialId credentialId = CredentialId.generate();
        CredentialDescriptor descriptor = credentials.create(
                new CredentialCreateRequest(
                        credentialId,
                        storeSubject,
                        "crewscope-test-" + connectionId,
                        provider.providerKey().value(),
                        Optional.of(connectionId.value()),
                        "MODEL_API_KEY",
                        Map.of(),
                        Optional.empty(),
                        actor),
                CredentialSecret.utf8("test-secret"));
        ModelConnection connection = ModelConnection.open(
                provider,
                connectionId,
                owner,
                provider.defaultEndpoint(),
                CHINA,
                new ModelCredentialBinding(
                        credentialId,
                        bindingSubject,
                        new ModelCredentialVersion(descriptor.secretVersion())),
                billingSubject,
                actor,
                NOW);
        return connections.register(connection);
    }

    /** Usage facts only — the credential service books its HANDLE_ISSUED audit into the same store. */
    private List<DomainEventEnvelope<? extends DomainEvent>> usageEvents() {
        return store.events.stream()
                .filter(envelope ->
                        "MODEL_USAGE_FACT_RECORDED".equals(envelope.eventType().value()))
                .toList();
    }

    private ModelUsageFactRecorded usagePayload(int index) {
        return assertInstanceOf(
                ModelUsageFactRecorded.class, usageEvents().get(index).payload());
    }

    /** Outbox rows of the usage facts only — HANDLE_ISSUED audit also reaches the outbox. */
    private long usageOutboxCount() {
        java.util.Set<UUID> usageEventIds = usageEvents().stream()
                .map(DomainEventEnvelope::eventId)
                .collect(java.util.stream.Collectors.toSet());
        return store.outbox.stream()
                .filter(event -> usageEventIds.contains(event.domainEventId()))
                .count();
    }

    // ------------------------------------------------------- model resolution (I01b enqueue face)

    @Test
    void resolveModelReturnsTheGovernanceChainModelWithoutSpendingHttp() {
        EmbeddingModelRevision model = service.resolveModel(organizationId, teamId);

        assertEquals("text-embedding-v4", model.modelKey());
        assertEquals(1024, model.dimension());
        assertEquals(1, model.revision());
        assertTrue(client.connections.isEmpty(),
                "model resolution must not spend an HTTP round trip");
        assertTrue(client.requests.isEmpty());
    }

    @Test
    void resolveModelFallsBackToTheOrganizationOwnedConnection() {
        connections.values.keySet().removeIf(id ->
                connections.values.get(id).owner().teamId().isPresent());
        registerConnection(
                dashscopeProvider,
                ModelConnectionOwner.organization(organizationId),
                ModelCredentialSubject.organization(organizationId),
                ModelBillingSubject.organization(organizationId),
                CredentialSubject.organization(organizationId));

        EmbeddingModelRevision model = service.resolveModel(organizationId, teamId);

        assertEquals("text-embedding-v4", model.modelKey(),
                "the ORGANIZATION fallback resolves the same model identity");
    }

    @Test
    void resolveModelAppliesTheTrainingPolicyAndPriceGates() {
        // Price gate: no effective price row for the otherwise usable model.
        prices.values.clear();
        DomainValidationException price = assertThrows(
                DomainValidationException.class,
                () -> service.resolveModel(organizationId, teamId));
        assertEquals("teamEmbedding.price", price.error().details().get("field"));

        // Policy gate: the only remaining connection belongs to a training-allowed provider.
        ModelProviderDefinition trainingAllowed = ModelProviderDefinition.publish(
                TRAINING_PROVIDER,
                "Training Provider",
                new ModelAdapterKey("openai-compatible"),
                new ModelEndpoint("https://training.example.com/v1"),
                java.util.Set.of(CHINA),
                new ModelDataPolicy(
                        ModelDataRetentionMode.PROVIDER_MANAGED,
                        Optional.empty(),
                        ModelTrainingUsagePolicy.PROVIDER_DEFAULT),
                actor,
                NOW);
        providers.register(trainingAllowed);
        ModelCatalogEntry trainingEntry = ModelCatalogEntry.publishInitial(
                trainingAllowed,
                ModelCatalogEntryId.generate(),
                new ModelId("training-embedding"),
                new ModelRevision("training-embedding"),
                "Training Provider Embedding",
                33_000,
                1,
                java.util.Set.of(new ModelCapability("embedding")),
                java.util.Set.of(CHINA),
                actor,
                NOW);
        catalogs.append(trainingEntry);
        prices.append(ModelPriceRevision.publish(
                trainingEntry.coordinate(),
                1,
                PRICE_EFFECTIVE_FROM,
                new ModelTokenPrice(
                        new BigDecimal("0.4"), new BigDecimal("0"), Optional.empty(), "CNY"),
                new ModelPriceSource("https://training.example.com/pricing"),
                actor,
                NOW));
        connections.values.clear();
        registerConnection(
                trainingAllowed,
                ModelConnectionOwner.team(initialization.team()),
                ModelCredentialSubject.team(organizationId, teamId),
                ModelBillingSubject.team(organizationId, teamId),
                CredentialSubject.team(organizationId, teamId));

        DomainValidationException policy = assertThrows(
                DomainValidationException.class,
                () -> service.resolveModel(organizationId, teamId));
        assertEquals("teamEmbedding.connection", policy.error().details().get("field"));
    }


    /** Programmable transport fake recording every resolved call. */
    private static final class FakeEmbeddingClient implements EmbeddingClient {

        final List<ModelConnection> connections = new ArrayList<>();
        final List<EmbeddingRequest> requests = new ArrayList<>();
        boolean succeed = true;
        ModelConnectionHealthFailureCode failureCode =
                ModelConnectionHealthFailureCode.RATE_LIMITED;
        int vectorDimension = TeamEmbeddingService.EMBEDDING_DIMENSION;
        boolean nonFinite = false;
        List<Attempt> attempts = List.of();

        @Override
        public EmbeddingCall embed(
                ModelConnection connection, EmbeddingRequest request,
                ProviderCredentialHandle credentialHandle) {
            connections.add(connection);
            requests.add(request);
            if (!succeed) {
                List<Attempt> reported = attempts.isEmpty()
                        ? List.of(new Attempt(1, false, ModelTokenUsage.unreported()))
                        : attempts;
                return new EmbeddingCall(false, Optional.of(failureCode), List.of(), reported);
            }
            List<float[]> vectors = new ArrayList<>();
            for (int i = 0; i < request.input().size(); i++) {
                float[] vector = new float[vectorDimension];
                Arrays.fill(vector, 0.25f);
                if (nonFinite) {
                    vector[0] = Float.NaN;
                }
                vectors.add(vector);
            }
            List<Attempt> reported = attempts.isEmpty()
                    ? List.of(new Attempt(1, true, ModelTokenUsage.unreported()))
                    : attempts;
            return new EmbeddingCall(true, Optional.empty(), vectors, reported);
        }
    }

    /** In-memory ports for the event and outbox stores. */
    private static final class Store implements DomainEventStore, OutboxRepository {

        final List<DomainEventEnvelope<? extends DomainEvent>> events = new ArrayList<>();
        final List<PendingOutboxEvent> outbox = new ArrayList<>();

        @Override
        public void append(DomainEventEnvelope<? extends DomainEvent> event) {
            events.add(event);
        }

        @Override
        public void enqueue(PendingOutboxEvent event) {
            outbox.add(event);
        }
    }

    private static final class DirectTransactionExecutor implements TransactionExecutor {
        @Override
        public <T> T required(Supplier<T> operation) {
            return operation.get();
        }
    }

    private final class FakeTeamRepository implements TeamRepository {
        @Override
        public Team create(Team team) {
            return team;
        }

        @Override
        public Optional<Team> findById(OrganizationId organization, TeamId id) {
            return Optional.of(initialization.team())
                    .filter(team -> team.organizationId().equals(organization)
                            && team.id().equals(id));
        }

        @Override
        public Optional<UninitializedTeam> findUninitializedById(
                OrganizationId organization, TeamId id) {
            return Optional.empty();
        }
    }

    private static final class FakeConnectionRepository implements ModelConnectionRepository {
        final Map<ModelConnectionId, ModelConnection> values = new LinkedHashMap<>();

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
            return values.values().stream()
                    .filter(value -> value.owner().equals(owner))
                    .sorted(Comparator.comparing(value -> value.id().toString()))
                    .toList();
        }

        @Override
        public List<ModelConnection> findByOwner(
                ModelConnectionOwner owner, int offset, int limit) {
            return findByOwner(owner);
        }
    }

    private static final class FakeProviderRepository
            implements ModelProviderDefinitionRepository {

        private final Map<ModelProviderKey, ModelProviderDefinition> values =
                new LinkedHashMap<>();

        @Override
        public ModelProviderDefinition register(ModelProviderDefinition definition) {
            values.put(definition.providerKey(), definition);
            return definition;
        }

        @Override
        public ModelProviderDefinition updateLifecycle(ModelProviderDefinition definition) {
            values.put(definition.providerKey(), definition);
            return definition;
        }

        @Override
        public Optional<ModelProviderDefinition> findByKey(ModelProviderKey providerKey) {
            return Optional.ofNullable(values.get(providerKey));
        }

        @Override
        public List<ModelProviderDefinition> findPage(int offset, int limit) {
            return List.copyOf(values.values());
        }
    }

    private static final class FakeCatalogRepository implements ModelCatalogEntryRepository {

        private final List<ModelCatalogEntry> values = new ArrayList<>();

        @Override
        public ModelCatalogEntry append(ModelCatalogEntry entry) {
            values.add(entry);
            return entry;
        }

        @Override
        public ModelCatalogEntry updateLifecycle(ModelCatalogEntry entry) {
            values.removeIf(value -> value.coordinate().equals(entry.coordinate()));
            values.add(entry);
            return entry;
        }

        @Override
        public Optional<ModelCatalogEntry> findByCoordinate(ModelCatalogCoordinate coordinate) {
            return values.stream()
                    .filter(value -> value.coordinate().equals(coordinate))
                    .findFirst();
        }

        @Override
        public Optional<ModelCatalogEntry> findByEntryRevision(
                ModelCatalogEntryId entryId, ModelCatalogRevision revision) {
            return values.stream()
                    .filter(value -> value.id().equals(entryId)
                            && value.catalogRevision().equals(revision))
                    .findFirst();
        }

        @Override
        public Optional<ModelCatalogEntry> findLatest(
                ModelProviderKey providerKey, ModelId modelId) {
            return values.stream()
                    .filter(value -> value.providerKey().equals(providerKey)
                            && value.modelId().equals(modelId))
                    .max(Comparator.comparing(ModelCatalogEntry::catalogRevision));
        }

        @Override
        public List<ModelCatalogEntry> findPage(
                ModelProviderKey providerKey, int offset, int limit) {
            return values.stream()
                    .filter(value -> value.providerKey().equals(providerKey))
                    .sorted(Comparator.comparing(ModelCatalogEntry::modelId)
                            .thenComparing(
                                    ModelCatalogEntry::catalogRevision,
                                    Comparator.reverseOrder()))
                    .toList();
        }
    }

    private static final class FakePriceRepository implements ModelPriceScheduleRepository {

        private final List<ModelPriceRevision> values = new ArrayList<>();

        @Override
        public ModelPriceRevision append(ModelPriceRevision priceRevision) {
            values.add(priceRevision);
            return priceRevision;
        }

        @Override
        public Optional<io.crewscope.domain.model.ModelPriceSchedule> findSchedule(
                ModelCatalogCoordinate coordinate) {
            return Optional.empty();
        }

        @Override
        public Optional<ModelPriceRevision> findEffectivePrice(
                ModelCatalogCoordinate coordinate, UtcTimestamp effectiveAt) {
            return values.stream()
                    .filter(value -> value.catalogCoordinate().equals(coordinate))
                    .filter(value -> value.effectiveFrom().compareTo(effectiveAt) <= 0)
                    .max(Comparator.comparingLong(ModelPriceRevision::revision));
        }
    }

    /** Minimal CredentialStore: create/describe/resolve only — the embedding path needs no more. */
    private static final class FakeCredentialStore implements CredentialStore {

        private final Map<CredentialReference, StoredCredential> values = new HashMap<>();

        @Override
        public CredentialDescriptor create(
                CredentialCreateRequest request, CredentialSecret secret) {
            CredentialDescriptor descriptor = new CredentialDescriptor(
                    request.credentialId(),
                    request.subject(),
                    request.credentialKey(),
                    request.providerKey(),
                    request.connectionRef(),
                    request.credentialType(),
                    request.metadata(),
                    CredentialStatus.ACTIVE,
                    request.expiresAt(),
                    Optional.empty(),
                    Optional.empty(),
                    "test-key",
                    "AES-256-GCM",
                    "1",
                    request.createdBy(),
                    request.createdBy(),
                    NOW,
                    NOW,
                    0,
                    0);
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
            StoredCredential stored = accessContext.allows(reference)
                    ? values.get(reference)
                    : null;
            if (stored == null) {
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
            throw new UnsupportedOperationException("rotation is out of scope here");
        }

        @Override
        public CredentialDescriptor revoke(
                CredentialReference reference,
                long expectedVersion,
                CredentialMutationContext mutationContext,
                CredentialRevocationReason reason) {
            throw new UnsupportedOperationException("revocation is out of scope here");
        }

        private record StoredCredential(CredentialDescriptor descriptor, byte[] secret) {}
    }
}
