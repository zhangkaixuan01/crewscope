package io.crewscope.application.observability;

import io.crewscope.application.event.json.DomainEventEnvelopeJsonCodec;
import io.crewscope.application.model.ModelCatalogEntryRepository;
import io.crewscope.application.model.ModelPriceScheduleRepository;
import io.crewscope.domain.model.ModelCatalogEntry;
import io.crewscope.domain.model.ModelId;
import io.crewscope.domain.model.ModelPriceRevision;
import io.crewscope.domain.model.ModelProviderKey;
import io.crewscope.domain.model.ModelTokenUsage;
import io.crewscope.domain.model.event.ModelUsageFactRecorded;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import tools.jackson.databind.ObjectMapper;

/**
 * Projects {@code ModelUsageFactRecorded} facts onto the monthly usage rollup
 * (M10-F03, S01 §3.9). Two entry paths share one projection body: the incremental
 * white-list consumer (inside the idempotent dispatcher transaction) and the rebuild
 * (delete-all plus a direct scan of the canonical event log, bypassing the dispatcher
 * so existing receipts keep guarding the incremental path).
 *
 * <p>Pricing semantics (contract §"rebuildability"): the price point is resolved at
 * projection time from the fact's {@code occurredAt} against the provider/model's
 * latest catalog revision — the fact stores no event-time price snapshot. A published
 * price revision therefore shows up after the next rebuild, and an unresolvable price
 * (no catalog entry, or no row effective at the fact's instant) leaves the tokens
 * counted but unbilled on an 'XXX' sentinel row, never billed as zero.
 */
public class ModelUsageRollupService {

    /** The single white-listed event type this projection consumes. */
    public static final String EVENT_TYPE = "MODEL_USAGE_FACT_RECORDED";
    private static final int REBUILD_BATCH = 500;

    /**
     * Serializes projection writers within this process: two concurrent rebuilds would
     * otherwise interleave their delete-and-replay sweeps and double-apply facts, and a
     * rebuild must not overlap an incremental delivery mid-statement. Interleaving a
     * rebuild against deliveries running in another process remains a documented
     * operational window — rerunning the rebuild in a quiet period converges, because
     * it is idempotent by construction.
     */
    private final ReentrantLock projectionLock = new ReentrantLock();

    private final ModelCatalogEntryRepository catalogs;
    private final ModelPriceScheduleRepository prices;
    private final ModelUsageRollupWriter rollup;
    private final ModelUsageFactScan scan;
    private final ZoneId reportingZone;
    private final ObjectMapper objectMapper;
    private final DomainEventEnvelopeJsonCodec eventCodec;

    public ModelUsageRollupService(
            ModelCatalogEntryRepository catalogs,
            ModelPriceScheduleRepository prices,
            ModelUsageRollupWriter rollup,
            ModelUsageFactScan scan,
            ZoneId reportingZone,
            ObjectMapper objectMapper) {
        this.catalogs = Objects.requireNonNull(catalogs, "catalogs");
        this.prices = Objects.requireNonNull(prices, "prices");
        this.rollup = Objects.requireNonNull(rollup, "rollup");
        this.scan = Objects.requireNonNull(scan, "scan");
        this.reportingZone = Objects.requireNonNull(reportingZone, "reportingZone");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.eventCodec = new DomainEventEnvelopeJsonCodec(this.objectMapper);
    }

    /** Projects one fact; the caller owns the transaction (dispatcher or rebuild). */
    public void project(
            OrganizationId organizationId, Optional<TeamId> teamId, ModelUsageFactRecorded fact) {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(fact, "fact");

        boolean unreported = isUnreported(fact.usage());
        ModelUsageRollupDelta delta = new ModelUsageRollupDelta(
                organizationId,
                teamId,
                ModelUsageMonthKey.of(fact.occurredAt(), reportingZone),
                fact.role().name(),
                fact.providerKey().value(),
                fact.modelId().value(),
                unreported ? Optional.empty() : resolvePricing(fact),
                fact.attempt(),
                unreported ? 0L : fact.usage().inputTokens(),
                unreported ? 0L : fact.usage().outputTokens(),
                unreported ? 0L : fact.usage().cachedTokens(),
                1L,
                unreported ? 1L : 0L,
                fact.occurredAt(),
                fact.occurredAt());
        projectionLock.lock();
        try {
            rollup.apply(delta);
        } finally {
            projectionLock.unlock();
        }
    }

    /**
     * Recomputes the whole rollup from the canonical event log: delete every row, then
     * re-project every persisted usage fact in event-id order. Idempotent by
     * construction — running it twice converges on the same table. Statements run
     * outside any wrapping transaction (each auto-commits), so a crashed rebuild can
     * leave a partially recomputed projection; rerunning it completes the convergence.
     * Holds the projection lock for the whole sweep, serializing it against other
     * rebuilds and incremental deliveries in this process.
     */
    public long rebuildAll() {
        projectionLock.lock();
        try {
            return replayAllLocked();
        } finally {
            projectionLock.unlock();
        }
    }

    private long replayAllLocked() {
        rollup.deleteAll();
        long projected = 0;
        UUID cursor = null;
        while (true) {
            List<ModelUsageFactScan.ScannedUsageFact> batch =
                    scan.findFactsAfter(cursor, REBUILD_BATCH);
            if (batch.isEmpty()) {
                return projected;
            }
            for (ModelUsageFactScan.ScannedUsageFact scanned : batch) {
                if (!EVENT_TYPE.equals(objectMapper.readTree(scanned.eventJson())
                        .path("eventType").asText())) {
                    throw new IllegalStateException(
                            "usage fact scan returned a non-usage event: "
                                    + scanned.eventId());
                }
                DomainEventEnvelope<ModelUsageFactRecorded> envelope =
                        eventCodec.decode(scanned.eventJson(), ModelUsageFactRecorded.class);
                // project() re-enters the already-held lock (ReentrantLock) — the sweep
                // keeps the lock across the whole delete-and-replay, not per fact.
                project(envelope.organizationId(), envelope.teamId(), envelope.payload());
                projected++;
            }
            cursor = batch.get(batch.size() - 1).eventId();
        }
    }

    /** The frozen contract: all-zero counters mean the Provider reported nothing. */
    private static boolean isUnreported(ModelTokenUsage usage) {
        // Cached is a subset of input, so input and output zero out exactly then.
        return usage.inputTokens() == 0 && usage.outputTokens() == 0;
    }

    private Optional<ModelUsageRollupDelta.Pricing> resolvePricing(ModelUsageFactRecorded fact) {
        // "Latest selectable catalog revision" (the embedding governance rule): the
        // newest revision of the provider/model pair, status not re-filtered here —
        // a retired entry keeps its append-only price rows so historical facts stay
        // billable instead of degrading to 'XXX' when governance moves on.
        return catalogs.findLatest(fact.providerKey(), fact.modelId())
                .flatMap(entry -> prices
                        .findEffectivePrice(entry.coordinate(), fact.occurredAt())
                        .map(price -> pricingOf(entry, price, fact)));
    }

    private static ModelUsageRollupDelta.Pricing pricingOf(
            ModelCatalogEntry entry, ModelPriceRevision price, ModelUsageFactRecorded fact) {
        ModelTokenUsage usage = fact.usage();
        return new ModelUsageRollupDelta.Pricing(
                entry.coordinate().entryId().value(),
                entry.coordinate().catalogRevision().value(),
                price.revision(),
                price.tokenPrice().currencyCode(),
                costOf(usage.inputTokens(), price.tokenPrice().inputPerMillionTokens()),
                costOf(usage.outputTokens(), price.tokenPrice().outputPerMillionTokens()),
                // A price row without a cached-input rate simply bills cached tokens as
                // part of input (V20 allows the NULL); zero, not UNKNOWN.
                price.tokenPrice().cachedInputPerMillionTokens()
                        .map(rate -> costOf(usage.cachedTokens(), rate))
                        .orElse(BigDecimal.ZERO));
    }

    private static BigDecimal costOf(long tokens, BigDecimal perMillionTokens) {
        // Exact by construction: dividing by 10^6 only moves the decimal point, so the
        // stored NUMERIC(24,12) never loses precision to rounding.
        return BigDecimal.valueOf(tokens)
                .multiply(Objects.requireNonNull(perMillionTokens))
                .movePointLeft(6)
                .stripTrailingZeros();
    }
}
