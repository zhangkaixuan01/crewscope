package io.crewscope.infrastructure.persistence.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.event.json.DomainEventEnvelopeJsonCodec;
import io.crewscope.application.event.publication.EventPublication;
import io.crewscope.application.model.DefaultPlatformModelCatalogInitializer;
import io.crewscope.application.observability.ModelUsageRollupService;
import io.crewscope.domain.model.ModelConnectionId;
import io.crewscope.domain.model.ModelId;
import io.crewscope.domain.model.ModelPriceRevision;
import io.crewscope.domain.model.ModelPriceSource;
import io.crewscope.domain.model.ModelProviderKey;
import io.crewscope.domain.model.ModelTokenPrice;
import io.crewscope.domain.model.ModelTokenUsage;
import io.crewscope.domain.model.ModelUsageFactId;
import io.crewscope.domain.model.ModelUsageRole;
import io.crewscope.domain.model.event.ModelUsageFactRecorded;
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
import io.crewscope.infrastructure.event.IdempotentEventDispatcher;
import io.crewscope.infrastructure.event.JdbcDomainEventJsonMapper;
import io.crewscope.infrastructure.persistence.event.JdbcDomainEventStore;
import io.crewscope.infrastructure.persistence.modelagent.JdbcModelRegistryRepositoryAdapter;
import io.crewscope.infrastructure.persistence.modelagent.ModelAgentPersistenceMapper;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * PostgreSQL contract for the F03a rollup projection chain (M10-F03): the white-listed
 * consumer projects through the idempotent dispatcher (receipt and increment commit
 * together, replay never double-counts), pricing resolves against the real platform
 * catalog seeds per currency and price revision, unresolvable prices land on the 'XXX'
 * sentinel with tokens counted but never billed as zero, the reporting zone owns the
 * month boundary, attempts and unreported facts stay separate grain rows, and the
 * rebuild replays the canonical {@code domain_event} log deterministically while
 * existing receipts keep guarding the incremental path.
 */
class ModelUsageRollupPersistenceIntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final ModelProviderKey DEEPSEEK = new ModelProviderKey("deepseek");
    private static final ModelId DEEPSEEK_FLASH = new ModelId("deepseek-flash");
    private static final ModelProviderKey DASHSCOPE = new ModelProviderKey("dashscope");
    private static final ModelId EMBEDDING_V4 = new ModelId("text-embedding-v4");
    private static final UtcTimestamp OCCURRED = UtcTimestamp.parse("2026-10-05T12:00:00Z");

    private static JdbcTemplate jdbc;
    private static JdbcModelRegistryRepositoryAdapter registry;
    private static JdbcDomainEventStore eventStore;
    private static DomainEventEnvelopeJsonCodec eventCodec;
    private static ModelUsageRollupService rollupService;
    private static ModelUsageRollupConsumer consumer;
    private static IdempotentEventDispatcher dispatcher;

    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();
    private final PrincipalId actorId = PrincipalId.generate();

    @BeforeAll
    static void migrateSchemaAndAssembleRealComponents() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .schemas("crewscope")
                .defaultSchema("crewscope")
                .createSchemas(true)
                .validateMigrationNaming(true)
                // The chain tip this test rides (V65 only rebuilds the inbox CHECK).
                .target(MigrationVersion.fromVersion("65"))
                .load()
                .migrate();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        ObjectMapper objectMapper = JsonMapper.builder().build();
        registry = new JdbcModelRegistryRepositoryAdapter(
                new NamedParameterJdbcTemplate(dataSource),
                new ModelAgentPersistenceMapper(new com.fasterxml.jackson.databind.ObjectMapper()));
        eventStore = new JdbcDomainEventStore(jdbc, objectMapper);
        eventCodec = new DomainEventEnvelopeJsonCodec(objectMapper);
        rollupService = new ModelUsageRollupService(
                registry, registry,
                new JdbcModelUsageRollupWriter(jdbc),
                new JdbcModelUsageFactScan(jdbc, new JdbcDomainEventJsonMapper(objectMapper)),
                SHANGHAI, objectMapper);
        consumer = new ModelUsageRollupConsumer(objectMapper, rollupService);
        dispatcher = new IdempotentEventDispatcher(
                jdbc, new DataSourceTransactionManager(dataSource), Clock.systemUTC());
    }

    @BeforeEach
    void resetAndSeedPlatformCatalog() {
        jdbc.update("""
                TRUNCATE TABLE crewscope.model_usage_monthly_rollup,
                    crewscope.team_budget_alert, crewscope.event_consumer_receipt,
                    crewscope.domain_event, crewscope.outbox_event,
                    crewscope.model_price_revision, crewscope.model_catalog_entry,
                    crewscope.model_provider_definition, crewscope.principal,
                    crewscope.team, crewscope.organization CASCADE
                """);
        jdbc.update(
                "INSERT INTO crewscope.organization (id, name, status) VALUES (?, ?, 'ACTIVE')",
                organizationId.value(), "Rollup Org");
        jdbc.update("""
                INSERT INTO crewscope.team (id, organization_id, name, status)
                VALUES (?, ?, 'Rollup Team', 'ACTIVE')
                """, teamId.value(), organizationId.value());
        jdbc.update("""
                INSERT INTO crewscope.principal (
                    id, organization_id, team_id, principal_type,
                    display_name, visibility, status
                ) VALUES (?, ?, ?, 'USER', ?, 'TEAM', 'ACTIVE')
                """, actorId.value(), organizationId.value(), teamId.value(), "Rollup Actor");
        // The real platform seeds: DeepSeek USD chat pricing plus DashScope CNY embeddings.
        new DefaultPlatformModelCatalogInitializer(registry, registry, registry)
                .initialize(actorId, UtcTimestamp.parse("2026-10-02T08:00:00Z"));
    }

    @Test
    void replaysNeverDoubleCountThroughConsumerReceipts() {
        DomainEventEnvelope<ModelUsageFactRecorded> event = usageEvent(Optional.of(teamId),
                fact(ModelUsageRole.CHAT_PRIMARY, DEEPSEEK, DEEPSEEK_FLASH,
                        new ModelTokenUsage(1_000_000, 500_000, 300_000, 1_500_000), OCCURRED));

        assertTrue(publish(event));
        assertFalse(publish(event), "the receipt must block the second delivery");

        Map<String, Object> row = singleRollupRow();
        assertEquals(1L, row.get("fact_count"));
        assertEquals(1_000_000L, row.get("input_tokens"));
        assertCost(row, "input_cost", "0.44");
    }

    @Test
    void factsWithoutAResolvablePriceLandOnTheXxxSentinel() {
        publish(usageEvent(Optional.of(teamId),
                fact(ModelUsageRole.EMBEDDING, DASHSCOPE,
                        new ModelId("model-without-a-catalog-entry"),
                        new ModelTokenUsage(7_000, 0, 0, 7_000), OCCURRED)));

        Map<String, Object> row = singleRollupRow();
        assertEquals("XXX", ((String) row.get("currency_code")).strip());
        assertNull(row.get("input_cost"));
        assertNull(row.get("catalog_entry_id"));
        assertEquals(7_000L, row.get("input_tokens"), "tokens stay counted, never billed as zero");
        assertEquals(0L, row.get("unreported_fact_count"));
    }

    @Test
    void pricedFactsSplitPerCurrencyAndPriceRevision() {
        // A second DashScope price revision effective 2026-10-20 splits the grain.
        registry.append(ModelPriceRevision.publish(
                registry.findLatest(DASHSCOPE, EMBEDDING_V4).orElseThrow().coordinate(), 2L,
                UtcTimestamp.parse("2026-10-20T00:00:00Z"),
                new ModelTokenPrice(
                        new BigDecimal("0.6"), new BigDecimal("0"), Optional.empty(), "CNY"),
                new ModelPriceSource("https://help.aliyun.com/zh/model-studio/embeddings"),
                actorId, UtcTimestamp.parse("2026-10-21T00:00:00Z")));

        publish(usageEvent(Optional.of(teamId),
                fact(ModelUsageRole.CHAT_PRIMARY, DEEPSEEK, DEEPSEEK_FLASH,
                        new ModelTokenUsage(1_000_000, 500_000, 300_000, 1_500_000),
                        UtcTimestamp.parse("2026-10-05T12:00:00Z"))));
        publish(usageEvent(Optional.of(teamId),
                fact(ModelUsageRole.EMBEDDING, DASHSCOPE, EMBEDDING_V4,
                        new ModelTokenUsage(2_000_000, 0, 0, 2_000_000),
                        UtcTimestamp.parse("2026-10-10T00:30:00Z"))));
        publish(usageEvent(Optional.of(teamId),
                fact(ModelUsageRole.EMBEDDING, DASHSCOPE, EMBEDDING_V4,
                        new ModelTokenUsage(2_000_000, 0, 0, 2_000_000),
                        UtcTimestamp.parse("2026-10-25T00:30:00Z"))));

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM crewscope.model_usage_monthly_rollup"
                        + " WHERE organization_id = ? ORDER BY currency_code, price_revision",
                organizationId.value());
        assertEquals(3, rows.size());
        Map<String, Object> usd = rows.get(2);
        assertEquals("USD", ((String) usd.get("currency_code")).strip());
        assertCost(usd, "input_cost", "0.44");
        assertCost(usd, "output_cost", "0.66");
        assertCost(usd, "cached_input_cost", "0.0042");
        // The same embedding model under two effective price points stays two grain rows.
        Map<String, Object> cnyRevisionOne = rows.get(0);
        Map<String, Object> cnyRevisionTwo = rows.get(1);
        assertCost(cnyRevisionOne, "input_cost", "1");
        assertEquals(1L, cnyRevisionOne.get("price_revision"));
        assertCost(cnyRevisionTwo, "input_cost", "1.2");
        assertEquals(2L, cnyRevisionTwo.get("price_revision"));
    }

    @Test
    void theShanghaiReportingZoneOwnsTheMonthBoundary() {
        // 2026-10-31T17:30Z is already November in Asia/Shanghai (the shipped default).
        publish(usageEvent(Optional.of(teamId),
                fact(ModelUsageRole.EMBEDDING, DASHSCOPE, EMBEDDING_V4,
                        new ModelTokenUsage(100, 0, 0, 100),
                        UtcTimestamp.parse("2026-10-31T17:30:00Z"))));

        assertEquals("2026-11", ((String) singleRollupRow().get("usage_month")).strip());
    }

    @Test
    void attemptsAndUnreportedFactsStaySeparateGrainRows() {
        publish(usageEvent(Optional.of(teamId),
                fact(ModelUsageRole.EMBEDDING, DASHSCOPE, EMBEDDING_V4, 1,
                        new ModelTokenUsage(1_000_000, 0, 0, 1_000_000),
                        UtcTimestamp.parse("2026-10-05T12:00:00Z"))));
        publish(usageEvent(Optional.of(teamId),
                fact(ModelUsageRole.EMBEDDING, DASHSCOPE, EMBEDDING_V4, 2,
                        ModelTokenUsage.unreported(),
                        UtcTimestamp.parse("2026-10-05T12:01:00Z"))));

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM crewscope.model_usage_monthly_rollup"
                        + " WHERE organization_id = ? ORDER BY attempt",
                organizationId.value());
        assertEquals(2, rows.size(), "attempt is part of the grain");
        assertEquals(1, rows.get(0).get("attempt"));
        assertEquals(1_000_000L, rows.get(0).get("input_tokens"));
        assertCost(rows.get(0), "input_cost", "0.5");
        assertEquals(2, rows.get(1).get("attempt"));
        assertEquals(0L, rows.get(1).get("input_tokens"));
        assertEquals("XXX", ((String) rows.get(1).get("currency_code")).strip());
        assertEquals(1L, rows.get(1).get("unreported_fact_count"));
    }

    @Test
    void probeFactsKeepTheTeamDimensionNull() {
        publish(usageEvent(Optional.empty(),
                fact(ModelUsageRole.EMBEDDING, DASHSCOPE, EMBEDDING_V4,
                        new ModelTokenUsage(500, 0, 0, 500), OCCURRED)));

        Map<String, Object> row = singleRollupRow();
        assertNull(row.get("team_id"));
        assertEquals(1L, row.get("fact_count"));
    }

    @Test
    void sameGrainFactsMergeAtomicallyAcrossApplications() {
        // Same grain (org/team/month/role/model/attempt/pricing), applied newest-first:
        // the ON CONFLICT arm must accumulate counters and converge the time window
        // regardless of application order.
        publish(usageEvent(Optional.of(teamId),
                fact(ModelUsageRole.CHAT_PRIMARY, DEEPSEEK, DEEPSEEK_FLASH,
                        new ModelTokenUsage(1_000_000, 500_000, 0, 1_500_000),
                        UtcTimestamp.parse("2026-10-05T12:05:00Z"))));
        publish(usageEvent(Optional.of(teamId),
                fact(ModelUsageRole.CHAT_PRIMARY, DEEPSEEK, DEEPSEEK_FLASH,
                        new ModelTokenUsage(500_000, 0, 0, 500_000),
                        UtcTimestamp.parse("2026-10-05T12:00:00Z"))));

        Map<String, Object> row = singleRollupRow();
        assertEquals(2L, row.get("fact_count"));
        assertEquals(1_500_000L, row.get("input_tokens"));
        assertEquals(500_000L, row.get("output_tokens"));
        assertCost(row, "input_cost", "0.66");
        assertCost(row, "output_cost", "0.66");
        assertEquals(Instant.parse("2026-10-05T12:00:00Z"),
                ((Timestamp) row.get("first_fact_at")).toInstant());
        assertEquals(Instant.parse("2026-10-05T12:05:00Z"),
                ((Timestamp) row.get("last_fact_at")).toInstant());
    }

    @Test
    void rebuildReplaysTheCanonicalLogAndExistingReceiptsKeepGuarding() {
        DomainEventEnvelope<ModelUsageFactRecorded> chat = usageEvent(Optional.of(teamId),
                fact(ModelUsageRole.CHAT_PRIMARY, DEEPSEEK, DEEPSEEK_FLASH,
                        new ModelTokenUsage(1_000_000, 500_000, 300_000, 1_500_000),
                        UtcTimestamp.parse("2026-10-05T12:00:00Z")));
        DomainEventEnvelope<ModelUsageFactRecorded> embedding = usageEvent(Optional.of(teamId),
                fact(ModelUsageRole.EMBEDDING, DASHSCOPE, EMBEDDING_V4,
                        new ModelTokenUsage(2_000_000, 0, 0, 2_000_000),
                        UtcTimestamp.parse("2026-10-10T00:30:00Z")));
        publish(chat);
        publish(embedding);
        List<Map<String, Object>> before = allRollupRows();

        long projected = rollupService.rebuildAll();

        assertEquals(2L, projected);
        assertEquals(before, allRollupRows(), "the rebuild converges on the same projection");
        // The incremental path stays receipt-guarded after a rebuild replayed the log.
        assertFalse(publish(chat));
        assertEquals(before, allRollupRows());
    }

    // ------------------------------------------------------------------ fixtures

    private ModelUsageFactRecorded fact(
            ModelUsageRole role, ModelProviderKey provider, ModelId model,
            ModelTokenUsage usage, UtcTimestamp at) {
        return fact(role, provider, model, 1, usage, at);
    }

    private ModelUsageFactRecorded fact(
            ModelUsageRole role, ModelProviderKey provider, ModelId model, int attempt,
            ModelTokenUsage usage, UtcTimestamp at) {
        return new ModelUsageFactRecorded(
                new ModelUsageFactId(UUID.randomUUID()), role, attempt,
                provider, model, new ModelConnectionId(UUID.randomUUID()), 0L, usage, at);
    }

    private DomainEventEnvelope<ModelUsageFactRecorded> usageEvent(
            Optional<TeamId> team, ModelUsageFactRecorded payload) {
        return new DomainEventEnvelope<>(
                UUID.randomUUID(), EventType.from("MODEL_USAGE_FACT_RECORDED"),
                SchemaVersion.V1, organizationId, team, Optional.empty(),
                AggregateReference.of("MODEL_USAGE_FACT", payload.callId()),
                payload.attempt(),
                new EventActor(EventActorType.SERVICE, Optional.of(actorId)),
                UUID.randomUUID(), Optional.empty(), Optional.empty(),
                payload.occurredAt(), payload);
    }

    /** Persists the canonical event once and delivers it through the dispatcher. */
    private boolean publish(DomainEventEnvelope<ModelUsageFactRecorded> envelope) {
        if (jdbc.queryForObject(
                "SELECT count(*) FROM crewscope.domain_event WHERE event_id = ?",
                Integer.class, envelope.eventId()) == 0) {
            eventStore.append(envelope);
        }
        return dispatcher.dispatch(consumer, new EventPublication(
                UUID.randomUUID(), envelope.eventId(), "crewscope.events",
                envelope.organizationId().toString(), 1,
                envelope.occurredAt(), eventCodec.encode(envelope)));
    }

    /** NUMERIC(24,12) keeps its storage scale, so value equality goes through compareTo. */
    private static void assertCost(Map<String, Object> row, String column, String expected) {
        assertEquals(0, new BigDecimal(expected).compareTo((BigDecimal) row.get(column)),
                column + " must equal " + expected);
    }

    private Map<String, Object> singleRollupRow() {
        return jdbc.queryForMap(
                "SELECT * FROM crewscope.model_usage_monthly_rollup WHERE organization_id = ?",
                organizationId.value());
    }

    private List<Map<String, Object>> allRollupRows() {
        return jdbc.queryForList(
                "SELECT * FROM crewscope.model_usage_monthly_rollup WHERE organization_id = ?"
                        + " ORDER BY team_id NULLS FIRST, usage_month, role, provider_key,"
                        + " model_id, currency_code, price_revision NULLS FIRST, attempt",
                organizationId.value());
    }
}
