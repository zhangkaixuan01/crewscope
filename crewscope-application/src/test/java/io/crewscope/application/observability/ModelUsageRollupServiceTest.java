package io.crewscope.application.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.event.json.DomainEventEnvelopeJsonCodec;
import io.crewscope.application.model.ModelCatalogEntryRepository;
import io.crewscope.application.model.ModelPriceScheduleRepository;
import io.crewscope.domain.model.ModelAdapterKey;
import io.crewscope.domain.model.ModelCapability;
import io.crewscope.domain.model.ModelCatalogCoordinate;
import io.crewscope.domain.model.ModelCatalogEntry;
import io.crewscope.domain.model.ModelCatalogEntryId;
import io.crewscope.domain.model.ModelCatalogRevision;
import io.crewscope.domain.model.ModelConnectionId;
import io.crewscope.domain.model.ModelDataPolicy;
import io.crewscope.domain.model.ModelEndpoint;
import io.crewscope.domain.model.ModelId;
import io.crewscope.domain.model.ModelProviderDefinition;
import io.crewscope.domain.model.ModelRegion;
import io.crewscope.domain.model.ModelRevision;
import io.crewscope.domain.model.ModelPriceRevision;
import io.crewscope.domain.model.ModelPriceSource;
import io.crewscope.domain.model.ModelProviderKey;
import io.crewscope.domain.model.ModelTokenPrice;
import io.crewscope.domain.model.ModelTokenUsage;
import io.crewscope.domain.model.ModelUsageFactId;
import io.crewscope.domain.model.ModelUsageRole;
import io.crewscope.domain.model.event.ModelUsageFactRecorded;
import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.event.AggregateReference;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.event.EventActor;
import io.crewscope.domain.shared.event.EventActorType;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Proves the F03a projection contract (M10-F03): unreported facts count without billing
 * zeros, priced facts carry exact per-million costs in the resolved currency, missing
 * prices leave tokens visible on 'XXX', the reporting zone owns month grouping, and
 * rebuild replays the canonical log deterministically.
 */
class ModelUsageRollupServiceTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final UtcTimestamp OCCURRED = UtcTimestamp.parse("2026-10-31T17:30:00Z");
    private static final ModelProviderKey PROVIDER = new ModelProviderKey("dashscope");
    private static final ModelId MODEL = new ModelId("text-embedding-v4");
    private static final ModelCatalogCoordinate COORDINATE = new ModelCatalogCoordinate(
            new ModelCatalogEntryId(UUID.randomUUID()), PROVIDER, MODEL, new ModelCatalogRevision(1));
    private static final ModelProviderDefinition DASHSCOPE_PROVIDER =
            ModelProviderDefinition.publish(
                    PROVIDER, "DashScope", new ModelAdapterKey("openai-compatible"),
                    new ModelEndpoint("https://dashscope.example.test/v1"),
                    Set.of(new ModelRegion("cn")), ModelDataPolicy.noRetention(),
                    PrincipalId.generate(), UtcTimestamp.parse("2026-10-01T00:00:00Z"));

    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();

    private ModelCatalogEntryRepository catalogs;
    private ModelPriceScheduleRepository prices;
    private RecordingWriter writer;
    private ModelUsageRollupService service;

    @BeforeEach
    void setUp() {
        catalogs = mock(ModelCatalogEntryRepository.class);
        prices = mock(ModelPriceScheduleRepository.class);
        writer = new RecordingWriter();
        service = new ModelUsageRollupService(
                catalogs, prices, writer, new InlineScan(List.of()), SHANGHAI,
                JsonMapper.builder().build());
    }

    @Test
    void unreportedFactsCountWithoutBillingZeros() {
        ModelUsageRollupDelta delta = projectFact(ModelTokenUsage.unreported());

        assertTrue(delta.pricing().isEmpty(), "an unreported fact never resolves a price row");
        assertEquals(0L, delta.inputTokens());
        assertEquals(0L, delta.outputTokens());
        assertEquals(0L, delta.cachedInputTokens());
        assertEquals(1L, delta.factCount());
        assertEquals(1L, delta.unreportedFactCount());
    }

    @Test
    void pricedFactsCarryExactPerMillionCostsInTheResolvedCurrency() {
        stubPrice(new BigDecimal("0.5"), new BigDecimal("0"), null, "CNY");

        ModelUsageRollupDelta delta = projectFact(
                new ModelTokenUsage(2_000_000, 500_000, 400_000, 2_500_000));

        Optional<ModelUsageRollupDelta.Pricing> pricing = delta.pricing();
        assertEquals("CNY", pricing.orElseThrow().currencyCode());
        assertEquals(new BigDecimal("1"), pricing.orElseThrow().inputCost());
        // A price row without a cached rate bills cached tokens as part of input: zero,
        // never UNKNOWN and never silently dropped from the input total.
        assertEquals(new BigDecimal("0"), pricing.orElseThrow().outputCost());
        assertEquals(BigDecimal.ZERO, pricing.orElseThrow().cachedInputCost());
        assertEquals(COORDINATE.entryId().value(), pricing.orElseThrow().catalogEntryId());
        assertEquals(1L, pricing.orElseThrow().catalogRevision());
        assertEquals(7L, pricing.orElseThrow().priceRevision());
        assertEquals(0L, delta.unreportedFactCount());
    }

    @Test
    void cachedTokensGetTheirOwnRateWhenThePriceRowCarriesOne() {
        stubPrice(new BigDecimal("0.44"), new BigDecimal("1.32"),
                new BigDecimal("0.014"), "USD");

        ModelUsageRollupDelta delta = projectFact(new ModelTokenUsage(1_000_000, 500_000,
                300_000, 1_500_000));

        ModelUsageRollupDelta.Pricing pricing = delta.pricing().orElseThrow();
        assertEquals(new BigDecimal("0.44"), pricing.inputCost());
        assertEquals(new BigDecimal("0.66"), pricing.outputCost());
        assertEquals(new BigDecimal("0.0042"), pricing.cachedInputCost());
    }

    @Test
    void missingCatalogOrEffectivePriceLeavesTokensVisibleUnbilled() {
        when(catalogs.findLatest(PROVIDER, MODEL)).thenReturn(Optional.empty());
        assertEquals(Optional.empty(), projectFact(
                new ModelTokenUsage(100, 20, 0, 120)).pricing());

        when(catalogs.findLatest(PROVIDER, MODEL)).thenReturn(Optional.of(entry()));
        when(prices.findEffectivePrice(COORDINATE, OCCURRED)).thenReturn(Optional.empty());
        ModelUsageRollupDelta delta = projectFact(new ModelTokenUsage(100, 20, 0, 120));
        assertEquals(Optional.empty(), delta.pricing());
        assertEquals(100L, delta.inputTokens(), "tokens stay counted, never billed as zero");
    }

    @Test
    void theReportingZoneOwnsMonthGrouping() {
        stubPrice(new BigDecimal("0.5"), new BigDecimal("0"), null, "CNY");

        // 2026-10-31T17:30Z: October in UTC, November in Shanghai (the frozen default).
        assertEquals("2026-11", projectFact(
                new ModelTokenUsage(1, 0, 0, 1)).usageMonth().value());

        RecordingWriter utcWriter = new RecordingWriter();
        ModelUsageRollupService utcService = new ModelUsageRollupService(
                catalogs, prices, utcWriter, new InlineScan(List.of()),
                ZoneOffset.UTC, JsonMapper.builder().build());
        utcService.project(organizationId, Optional.of(teamId), fact(new ModelTokenUsage(
                1, 0, 0, 1)));
        assertEquals("2026-10", utcWriter.deltas.get(0).usageMonth().value());
    }

    @Test
    void rebuildDeletesThenReplaysTheCanonicalLogDeterministically() {
        stubPrice(new BigDecimal("0.5"), new BigDecimal("0"), null, "CNY");
        DomainEventEnvelope<DomainEvent> first = usageEnvelope(
                fact(new ModelTokenUsage(1_000_000, 0, 0, 1_000_000)));
        DomainEventEnvelope<DomainEvent> second = usageEnvelope(
                fact(ModelTokenUsage.unreported()));
        DomainEventEnvelope<DomainEvent> foreign = usageEnvelope(
                new ModelUsageFactRecorded(
                        new ModelUsageFactId(UUID.randomUUID()),
                        ModelUsageRole.DISTILLATION, 3, PROVIDER, MODEL,
                        new ModelConnectionId(UUID.randomUUID()), 0,
                        ModelTokenUsage.unreported(), OCCURRED));
        ObjectMapper mapper = JsonMapper.builder().build();
        DomainEventEnvelopeJsonCodec codec = new DomainEventEnvelopeJsonCodec(mapper);
        InlineScan scan = new InlineScan(List.of(
                new ModelUsageFactScan.ScannedUsageFact(UUID.randomUUID(), codec.encode(first)),
                new ModelUsageFactScan.ScannedUsageFact(UUID.randomUUID(), codec.encode(second)),
                new ModelUsageFactScan.ScannedUsageFact(UUID.randomUUID(), codec.encode(foreign))));
        ModelUsageRollupService rebuildService = new ModelUsageRollupService(
                catalogs, prices, writer, scan, ZoneOffset.UTC, mapper);

        long projected = rebuildService.rebuildAll();

        assertEquals(3L, projected);
        assertEquals(1L, writer.deleted);
        assertEquals(3L, writer.deltas.size());
        // The replayed deltas group per attempt exactly like the incremental path.
        assertEquals(1, writer.deltas.get(0).attempt());
        assertEquals(3, writer.deltas.get(2).attempt());
        assertEquals(Optional.of(teamId), writer.deltas.get(2).teamId());
        assertEquals("2026-10", writer.deltas.get(0).usageMonth().value());
    }

    @Test
    void rebuildRejectsScanLeakageOfForeignEvents() {
        ObjectMapper mapper = JsonMapper.builder().build();
        DomainEventEnvelope<DomainEvent> foreign = new DomainEventEnvelope<>(
                UUID.randomUUID(), EventType.from("KNOWLEDGE_ENTRY_CREATED"), SchemaVersion.V1,
                organizationId, Optional.of(teamId), Optional.empty(),
                AggregateReference.of("KNOWLEDGE_ENTRY", TeamId.generate()),
                1L, new EventActor(EventActorType.SERVICE, Optional.empty()),
                UUID.randomUUID(), Optional.empty(), Optional.empty(),
                OCCURRED, new ForeignEvent("foreign"));
        InlineScan scan = new InlineScan(List.of(new ModelUsageFactScan.ScannedUsageFact(
                UUID.randomUUID(),
                new DomainEventEnvelopeJsonCodec(mapper).encode(foreign))));
        ModelUsageRollupService rebuildService = new ModelUsageRollupService(
                catalogs, prices, writer, scan, ZoneOffset.UTC, mapper);

        assertThrows(IllegalStateException.class, rebuildService::rebuildAll);
    }

    @Test
    void concurrentRebuildsSerializeIntoNonOverlappingSweeps() throws Exception {
        stubPrice(new BigDecimal("0.5"), new BigDecimal("0"), null, "CNY");
        ObjectMapper mapper = JsonMapper.builder().build();
        DomainEventEnvelopeJsonCodec codec = new DomainEventEnvelopeJsonCodec(mapper);
        InlineScan scan = new InlineScan(List.of(
                new ModelUsageFactScan.ScannedUsageFact(
                        UUID.randomUUID(), codec.encode(usageEnvelope(fact(
                                new ModelTokenUsage(1_000_000, 0, 0, 1_000_000))))),
                new ModelUsageFactScan.ScannedUsageFact(
                        UUID.randomUUID(), codec.encode(usageEnvelope(fact(
                                ModelTokenUsage.unreported()))))));
        SweepRecordingWriter sweepWriter = new SweepRecordingWriter();
        ModelUsageRollupService rebuildService = new ModelUsageRollupService(
                catalogs, prices, sweepWriter, scan, ZoneOffset.UTC, mapper);

        Thread first = new Thread(rebuildService::rebuildAll, "rebuild-1");
        Thread second = new Thread(rebuildService::rebuildAll, "rebuild-2");
        first.start();
        second.start();
        first.join();
        second.join();

        // Without the projection lock the two delete-and-replay sweeps interleave into
        // double-applied facts; serialized, the action log is exactly two complete sweeps.
        assertEquals(List.of("delete", "apply", "apply", "delete", "apply", "apply"),
                List.copyOf(sweepWriter.actions));
    }

    // ------------------------------------------------------------------ fixtures

    /** Minimal decodable payload so the codec round-trips a non-usage event. */
    record ForeignEvent(String marker) implements DomainEvent {
        ForeignEvent {
            if (marker == null || marker.isBlank()) {
                throw new IllegalArgumentException("marker");
            }
        }
    }

    private static final class RecordingWriter implements ModelUsageRollupWriter {
        final List<ModelUsageRollupDelta> deltas = new ArrayList<>();
        long deleted;

        @Override
        public void apply(ModelUsageRollupDelta delta) {
            deltas.add(delta);
        }

        @Override
        public void deleteAll() {
            deleted++;
        }
    }

    private static final class InlineScan implements ModelUsageFactScan {
        private final List<ScannedUsageFact> facts;

        InlineScan(List<ScannedUsageFact> facts) {
            this.facts = facts;
        }

        @Override
        public List<ScannedUsageFact> findFactsAfter(UUID afterEventId, int limit) {
            if (afterEventId == null) {
                return facts.stream().limit(limit).toList();
            }
            return List.of();
        }
    }

    /** Records the delete/apply order across threads to prove sweep serialization. */
    private static final class SweepRecordingWriter implements ModelUsageRollupWriter {
        final List<String> actions = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void apply(ModelUsageRollupDelta delta) {
            actions.add("apply");
            Thread.yield();
        }

        @Override
        public void deleteAll() {
            actions.add("delete");
            Thread.yield();
        }
    }

    private ModelUsageRollupDelta projectFact(ModelTokenUsage usage) {
        service.project(organizationId, Optional.of(teamId), fact(usage));
        return writer.deltas.get(writer.deltas.size() - 1);
    }

    private ModelUsageFactRecorded fact(ModelTokenUsage usage) {
        return new ModelUsageFactRecorded(
                new ModelUsageFactId(UUID.randomUUID()),
                ModelUsageRole.EMBEDDING, 1, PROVIDER, MODEL,
                new ModelConnectionId(UUID.randomUUID()), 4L, usage, OCCURRED);
    }

    private DomainEventEnvelope<DomainEvent> usageEnvelope(ModelUsageFactRecorded payload) {
        return new DomainEventEnvelope<>(
                UUID.randomUUID(), EventType.from("MODEL_USAGE_FACT_RECORDED"), SchemaVersion.V1,
                organizationId, Optional.of(teamId), Optional.empty(),
                AggregateReference.of("MODEL_USAGE_FACT", payload.callId()),
                payload.attempt(),
                new EventActor(EventActorType.USER, Optional.of(PrincipalId.generate())),
                UUID.randomUUID(), Optional.empty(), Optional.empty(),
                payload.occurredAt(), payload);
    }

    private ModelCatalogEntry entry() {
        return ModelCatalogEntry.publishInitial(
                DASHSCOPE_PROVIDER,
                COORDINATE.entryId(),
                MODEL,
                new ModelRevision("text-embedding-v4-20261001"),
                "DashScope text-embedding-v4",
                128_000,
                8_192,
                Set.of(new ModelCapability("text.embedding")),
                Set.of(new ModelRegion("cn")),
                PrincipalId.generate(),
                UtcTimestamp.parse("2026-10-01T00:00:00Z"));
    }

    private void stubPrice(
            BigDecimal input, BigDecimal output, BigDecimal cached, String currency) {
        when(catalogs.findLatest(PROVIDER, MODEL)).thenReturn(Optional.of(entry()));
        when(prices.findEffectivePrice(COORDINATE, OCCURRED))
                .thenReturn(Optional.of(ModelPriceRevision.publish(
                        COORDINATE, 7L, UtcTimestamp.parse("2026-10-01T00:00:00Z"),
                        new ModelTokenPrice(input, output,
                                Optional.ofNullable(cached), currency),
                        new ModelPriceSource("https://example.test/pricing"),
                        PrincipalId.generate(), UtcTimestamp.parse("2026-10-01T00:00:00Z"))));
    }
}
