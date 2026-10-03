package io.crewscope.application.embedding;

import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.event.PendingOutboxEvent;
import io.crewscope.application.model.EmbeddingCapabilityProbe;
import io.crewscope.application.model.ModelCatalogEntryRepository;
import io.crewscope.application.model.ModelConnectionCredentialService;
import io.crewscope.application.model.ModelConnectionRepository;
import io.crewscope.application.model.ModelPriceScheduleRepository;
import io.crewscope.application.model.ModelProviderDefinitionRepository;
import io.crewscope.application.model.OpenProviderCredentialHandleRequest;
import io.crewscope.application.model.ProviderCredentialHandle;
import io.crewscope.application.retrieval.KnowledgeEmbeddingExecutor;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.model.ModelCapability;
import io.crewscope.domain.model.ModelCatalogEntry;
import io.crewscope.domain.model.ModelConnection;
import io.crewscope.domain.model.ModelConnectionHealthFailureCode;
import io.crewscope.domain.model.ModelConnectionId;
import io.crewscope.domain.model.ModelConnectionOwner;
import io.crewscope.domain.model.ModelConnectionStatus;
import io.crewscope.domain.model.ModelCredentialVersion;
import io.crewscope.domain.model.ModelId;
import io.crewscope.domain.model.ModelProviderDefinition;
import io.crewscope.domain.model.ModelRegistryStatus;
import io.crewscope.domain.model.ModelTrainingUsagePolicy;
import io.crewscope.domain.model.ModelUsageFactId;
import io.crewscope.domain.model.ModelUsageRole;
import io.crewscope.domain.model.event.ModelUsageFactRecorded;
import io.crewscope.domain.retrieval.EmbeddingModelRevision;
import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.event.AggregateReference;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.event.EventActor;
import io.crewscope.domain.shared.event.EventActorType;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.Team;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Team-scoped embedding use case (M10-I01a): resolves the Team's usable embedding model
 * through the full existing governance chain (TEAM-owned before ORGANIZATION-owned
 * connections, ACTIVE provider, an ACTIVE catalog entry carrying the embedding capability,
 * a non-training outbound data policy and an effective price row), then performs one
 * bounded OpenAI-compatible {@code POST /embeddings} call through {@link EmbeddingClient}
 * and records one {@code MODEL_USAGE_FACT_RECORDED(EMBEDDING)} per real provider attempt —
 * including the attempts of a call that ultimately fails delivery.
 *
 * <p>Pre-validation (batch size, blank items, per-item length) happens before the
 * credential handle is opened, so malformed batches never spend an HTTP round trip.
 */
public final class TeamEmbeddingService implements KnowledgeEmbeddingExecutor {

    /** Product-frozen embedding dimension (S01 §3.2: text-embedding-v4 @ 1024). */
    public static final int EMBEDDING_DIMENSION = 1024;

    /** Catalog capability marking a model entry usable for embeddings (S01 §3.2). */
    static final ModelCapability EMBEDDING_CAPABILITY = new ModelCapability("embedding");

    /** Purpose bound into every credential handle opened for embedding delivery. */
    static final String HANDLE_PURPOSE = "model:embedding:embed";

    private static final String USAGE_AGGREGATE_TYPE = "MODEL_USAGE_FACT";
    private static final String CALL_ID_NAMESPACE = "io.crewscope/knowledge-embedding-call-v1/";
    private static final String USAGE_EVENT_ID_NAMESPACE = "io.crewscope/model-usage/event/";
    private static final int CATALOG_SCAN_LIMIT = 50;

    private final TeamRepository teams;
    private final ModelConnectionRepository connections;
    private final ModelProviderDefinitionRepository providers;
    private final ModelCatalogEntryRepository catalogs;
    private final ModelPriceScheduleRepository prices;
    private final ModelConnectionCredentialService credentials;
    private final EmbeddingClient client;
    private final DomainEventStore events;
    private final OutboxRepository outbox;
    private final TransactionExecutor transactions;
    private final TimeProvider timeProvider;

    public TeamEmbeddingService(
            TeamRepository teams,
            ModelConnectionRepository connections,
            ModelProviderDefinitionRepository providers,
            ModelCatalogEntryRepository catalogs,
            ModelPriceScheduleRepository prices,
            ModelConnectionCredentialService credentials,
            EmbeddingClient client,
            DomainEventStore events,
            OutboxRepository outbox,
            TransactionExecutor transactions,
            TimeProvider timeProvider) {
        this.teams = Objects.requireNonNull(teams, "teams");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.providers = Objects.requireNonNull(providers, "providers");
        this.catalogs = Objects.requireNonNull(catalogs, "catalogs");
        this.prices = Objects.requireNonNull(prices, "prices");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.client = Objects.requireNonNull(client, "client");
        this.events = Objects.requireNonNull(events, "events");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    /** Delivers one embedding batch, or throws after recording the spent usage facts. */
    @Override
    public EmbeddingBatchResult embed(TeamEmbeddingCommand command) {
        TeamEmbeddingCommand required = Objects.requireNonNull(command, "command");
        ResolvedEmbedding resolved = transactions.required(() -> resolveEmbedding(required));
        EmbeddingClient.EmbeddingCall call;
        try (ProviderCredentialHandle handle = credentials.openHandle(
                new OpenProviderCredentialHandleRequest(
                        required.organizationId(),
                        resolved.connection().id(),
                        resolved.connection().version(),
                        resolved.connection().credentialBinding().credentialVersion(),
                        required.actor(),
                        HANDLE_PURPOSE,
                        required.correlationId()))) {
            call = client.embed(resolved.connection(), resolved.request(), handle);
        }
        transactions.required(() -> recordUsageFacts(
                required.commandId(),
                required.organizationId(),
                Optional.of(required.teamId()),
                EventActor.principal(EventActorType.SERVICE, required.actor()),
                required.correlationId(),
                resolved,
                call));
        if (!call.delivered()) {
            throw new EmbeddingDeliveryException(
                    call.failureCode().orElse(ModelConnectionHealthFailureCode.PROVIDER_REJECTED));
        }
        return validatedResult(resolved, call);
    }

    /**
     * One real-embedding verification probe for a connection whose provider carries an
     * ACTIVE embedding catalog entry (S01 §3.2: a /models 200 cannot prove embedding
     * capability). Returns {@link Optional#empty()} when the probe does not apply, so
     * verification behavior is unchanged for providers without embedding models.
     */
    public Optional<EmbeddingCapabilityProbe.Outcome> probeCapability(
            ModelProviderDefinition provider,
            ModelConnection connection,
            ProviderCredentialHandle handle,
            UUID correlationId) {
        ModelProviderDefinition requiredProvider = Objects.requireNonNull(provider, "provider");
        ModelConnection requiredConnection = Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(correlationId, "correlationId");
        ModelCatalogEntry entry = embeddingCapableEntry(requiredProvider).orElse(null);
        if (entry == null) {
            return Optional.empty();
        }
        EmbeddingClient.EmbeddingRequest request = new EmbeddingClient.EmbeddingRequest(
                entry.modelId().value(),
                EMBEDDING_DIMENSION,
                List.of(EmbeddingCapabilityProbe.PROBE_INPUT));
        EmbeddingClient.EmbeddingCall call = client.embed(requiredConnection, request, handle);
        // The probe has no idempotency-key command behind it: the deterministic call id is
        // derived from the connection coordinates it verified, so repeated verifies of the
        // same connection credential version stay deduplicated while a rotation starts a
        // new series. An ORGANIZATION-owned connection books its usage without a Team scope.
        UUID commandId = stableProbeCommandId(
                requiredConnection.id(),
                requiredConnection.credentialBinding().credentialVersion());
        transactions.required(() -> recordUsageFacts(
                commandId,
                requiredConnection.organizationId(),
                requiredConnection.owner().teamId(),
                EventActor.anonymousService(),
                correlationId,
                new ResolvedEmbedding(requiredConnection, entry, request),
                call));
        if (call.delivered()) {
            return Optional.of(EmbeddingCapabilityProbe.Outcome.success());
        }
        return Optional.of(EmbeddingCapabilityProbe.Outcome.failed(
                call.failureCode().orElse(ModelConnectionHealthFailureCode.PROVIDER_REJECTED)));
    }

    /**
     * Resolves the Team's currently usable embedding model through the full governance
     * chain without spending an HTTP round trip (I01b enqueue face): the resolved
     * revision is frozen into repository index keys, and the worker later rejects a
     * delivered batch whose revision drifted from the frozen one.
     */
    @Override
    public EmbeddingModelRevision resolveModel(OrganizationId organizationId, TeamId teamId) {
        return transactions.required(() -> resolveCandidate(organizationId, teamId))
                .model();
    }

    // ---------------------------------------------------------------- resolution

    private ResolvedEmbedding resolveEmbedding(TeamEmbeddingCommand command) {
        ResolvedCandidate candidate =
                resolveCandidate(command.organizationId(), command.teamId());
        EmbeddingClient.EmbeddingRequest request = new EmbeddingClient.EmbeddingRequest(
                candidate.entry().modelId().value(), EMBEDDING_DIMENSION, command.inputs());
        return new ResolvedEmbedding(candidate.connection(), candidate.entry(), request);
    }

    private ResolvedCandidate resolveCandidate(OrganizationId organizationId, TeamId teamId) {
        Team team = teams.findById(organizationId, teamId)
                .filter(Team::isActive)
                .orElseThrow(() -> new AggregateNotFoundException("Team", teamId));
        boolean priceMissing = false;
        for (ModelConnection connection : usableConnections(team)) {
            if (connection.status() != ModelConnectionStatus.ACTIVE) {
                continue;
            }
            ModelProviderDefinition provider = providers.findByKey(connection.providerKey())
                    .filter(value -> value.status() == ModelRegistryStatus.ACTIVE)
                    .orElse(null);
            if (provider == null) {
                continue;
            }
            // Outbound hard gate: a provider allowed to train on Team content never receives it.
            if (provider.dataPolicy().trainingUsagePolicy()
                    != ModelTrainingUsagePolicy.PROHIBITED) {
                continue;
            }
            ModelCatalogEntry entry = embeddingCapableEntry(provider).orElse(null);
            if (entry == null) {
                continue;
            }
            if (prices.findEffectivePrice(entry.coordinate(), timeProvider.now()).isEmpty()) {
                priceMissing = true;
                continue;
            }
            return new ResolvedCandidate(connection, entry);
        }
        if (priceMissing) {
            throw new DomainValidationException(
                    "teamEmbedding.price",
                    "no ACTIVE embedding catalog entry carries an effective price (PRICE_UNAVAILABLE)");
        }
        throw new DomainValidationException(
                "teamEmbedding.connection",
                "no usable embedding model connection for this Team");
    }

    /** TEAM-owned connections first, then ORGANIZATION-owned, each in stable id order. */
    private List<ModelConnection> usableConnections(Team team) {
        List<ModelConnection> result = new ArrayList<>();
        connections.findByOwner(ModelConnectionOwner.team(team)).stream()
                .sorted(Comparator.comparing(value -> value.id().toString()))
                .forEach(result::add);
        connections.findByOwner(ModelConnectionOwner.organization(team.organizationId()))
                .stream()
                .filter(value -> result.stream()
                        .noneMatch(existing -> existing.id().equals(value.id())))
                .sorted(Comparator.comparing(value -> value.id().toString()))
                .forEach(result::add);
        return result;
    }

    /**
     * Latest revision of each model, keeping the first ACTIVE entry with embeddings.
     * "Latest" leans on {@code findPage} returning the newest catalog revision first —
     * the JDBC adapter sorts by catalog_revision DESC; a future adapter must keep that
     * ordering or resolution silently pins an older revision.
     */
    private Optional<ModelCatalogEntry> embeddingCapableEntry(ModelProviderDefinition provider) {
        Set<ModelId> seenModels = new HashSet<>();
        for (ModelCatalogEntry candidate
                : catalogs.findPage(provider.providerKey(), 0, CATALOG_SCAN_LIMIT)) {
            if (!seenModels.add(candidate.modelId())) {
                continue;
            }
            if (candidate.status() == ModelRegistryStatus.ACTIVE
                    && candidate.capabilities().contains(EMBEDDING_CAPABILITY)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    // ---------------------------------------------------------------- usage facts

    private Void recordUsageFacts(
            UUID commandId,
            OrganizationId organizationId,
            Optional<TeamId> teamScope,
            EventActor actor,
            UUID correlationId,
            ResolvedEmbedding resolved,
            EmbeddingClient.EmbeddingCall call) {
        UtcTimestamp now = timeProvider.now();
        for (EmbeddingClient.Attempt attemptUsage : call.attempts()) {
            ModelUsageFactId callId = stableCallId(commandId, attemptUsage.attempt());
            ModelUsageFactRecorded payload = new ModelUsageFactRecorded(
                    callId,
                    ModelUsageRole.EMBEDDING,
                    attemptUsage.attempt(),
                    resolved.connection().providerKey(),
                    resolved.entry().modelId(),
                    resolved.connection().id(),
                    resolved.connection().version(),
                    attemptUsage.usage(),
                    now);
            DomainEventEnvelope<DomainEvent> event =
                    new DomainEventEnvelope<>(
                            stableUsageEventId(callId.value()),
                            EventType.from("MODEL_USAGE_FACT_RECORDED"),
                            SchemaVersion.V1,
                            organizationId,
                            teamScope,
                            Optional.empty(),
                            AggregateReference.of(USAGE_AGGREGATE_TYPE, callId),
                            attemptUsage.attempt(),
                            actor,
                            correlationId,
                            Optional.empty(),
                            Optional.empty(),
                            now,
                            payload);
            events.append(event);
            outbox.enqueue(PendingOutboxEvent.fromDomain(UUID.randomUUID(), event));
        }
        return null;
    }

    private EmbeddingBatchResult validatedResult(
            ResolvedEmbedding resolved, EmbeddingClient.EmbeddingCall call) {
        List<float[]> vectors = call.vectors();
        if (vectors.size() != resolved.request().input().size()) {
            throw new EmbeddingDeliveryException(
                    ModelConnectionHealthFailureCode.PROVIDER_REJECTED);
        }
        for (float[] vector : vectors) {
            if (vector.length != EMBEDDING_DIMENSION) {
                throw new EmbeddingDeliveryException(
                        ModelConnectionHealthFailureCode.PROVIDER_REJECTED);
            }
            for (float component : vector) {
                if (!Float.isFinite(component)) {
                    throw new EmbeddingDeliveryException(
                            ModelConnectionHealthFailureCode.PROVIDER_REJECTED);
                }
            }
        }
        EmbeddingModelRevision model = resolved.model();
        return new EmbeddingBatchResult(
                vectors, model, resolved.entry().coordinate(),
                resolved.connection().id(), resolved.connection().version());
    }

    private static ModelUsageFactId stableCallId(UUID commandId, int attempt) {
        String source = CALL_ID_NAMESPACE + commandId + "/" + attempt;
        return new ModelUsageFactId(
                UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8)));
    }

    private static UUID stableProbeCommandId(
            ModelConnectionId connectionId, ModelCredentialVersion credentialVersion) {
        String source = CALL_ID_NAMESPACE + "verify/" + connectionId + "/" + credentialVersion;
        return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8));
    }

    private static UUID stableUsageEventId(UUID callId) {
        String source = USAGE_EVENT_ID_NAMESPACE + callId;
        return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8));
    }

    private record ResolvedEmbedding(
            ModelConnection connection,
            ModelCatalogEntry entry,
            EmbeddingClient.EmbeddingRequest request) {

        EmbeddingModelRevision model() {
            return embeddingModelRevision(entry);
        }
    }

    private record ResolvedCandidate(
            ModelConnection connection,
            ModelCatalogEntry entry) {

        EmbeddingModelRevision model() {
            return embeddingModelRevision(entry);
        }
    }

    private static EmbeddingModelRevision embeddingModelRevision(ModelCatalogEntry entry) {
        return new EmbeddingModelRevision(
                entry.modelId().value(), EMBEDDING_DIMENSION, entry.catalogRevision().value());
    }
}
