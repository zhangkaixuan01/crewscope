package io.crewscope.infrastructure.event.projection;

import io.crewscope.application.observability.OperationalTelemetry;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bounded, restartable shadow-generation replay coordinator. */
public final class ProjectionSupervisor {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProjectionSupervisor.class);

    private final JdbcProjectionSupervisorStore store;
    private final Map<String, ProjectionHistoryReplayer> replayers;
    private final ProjectionSupervisorProperties properties;
    private final TimeProvider timeProvider;
    private final OperationalTelemetry telemetry;

    public ProjectionSupervisor(
            JdbcProjectionSupervisorStore store,
            List<GenerationAwareProjectionHandler> handlers,
            GenerationAwareProjectionRunnerFactory runnerFactory,
            JdbcProjectionEventHistoryStore historyStore,
            ProjectionSupervisorProperties properties,
            TimeProvider timeProvider) {
        this(store, handlers, runnerFactory, historyStore, properties, timeProvider,
                OperationalTelemetry.noop());
    }

    public ProjectionSupervisor(
            JdbcProjectionSupervisorStore store,
            List<GenerationAwareProjectionHandler> handlers,
            GenerationAwareProjectionRunnerFactory runnerFactory,
            JdbcProjectionEventHistoryStore historyStore,
            ProjectionSupervisorProperties properties,
            TimeProvider timeProvider,
            OperationalTelemetry telemetry) {
        this.store = Objects.requireNonNull(store, "store");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.properties.validate();
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        Map<String, ProjectionHistoryReplayer> mapped = new LinkedHashMap<>();
        for (GenerationAwareProjectionHandler handler : List.copyOf(handlers)) {
            String name = handler.definition().name().value();
            ProjectionHistoryReplayer previous = mapped.put(
                    name,
                    new ProjectionHistoryReplayer(historyStore, runnerFactory.create(handler)));
            if (previous != null) {
                throw new IllegalStateException("Duplicate generation-aware projection: " + name);
            }
        }
        this.replayers = Map.copyOf(mapped);
    }

    /** Runs at most one history page per claimed Generation to keep scheduler latency bounded. */
    public ProjectionSupervisorRunResult runOnce() {
        UtcTimestamp now = timeProvider.now();
        List<ProjectionSupervisorClaim> claims = store.claim(
                properties.getInstanceId(), now, properties.getLeaseDuration(),
                properties.getClaimLimit());
        int progressed = 0;
        int caughtUp = 0;
        int interrupted = 0;
        for (ProjectionSupervisorClaim claim : claims) {
            OperationalTelemetry.Observation observation = telemetry.start(
                    OperationalTelemetry.Request.projection(
                            metricProjection(claim.generationKey().projectionName().value())));
            ProjectionHistoryReplayer replayer = replayers.get(
                    claim.generationKey().projectionName().value());
            if (replayer == null) {
                store.interrupt(claim, timeProvider.now());
                interrupted++;
                observation.complete(
                        OperationalTelemetry.Outcome.REJECTED,
                        OperationalTelemetry.ErrorCode.HANDLER_MISSING);
                LOGGER.warn("No runtime handler is registered for projection {}",
                        claim.generationKey().projectionName().value());
                continue;
            }
            try {
                ProjectionReplayBatchResult page = replayer.replayPage(
                        claim.generationLease(), claim.cursor(), properties.getPageSize());
                if (page.leaseRejected()) {
                    store.interrupt(claim, timeProvider.now());
                    interrupted++;
                    observation.complete(
                            OperationalTelemetry.Outcome.REJECTED,
                            OperationalTelemetry.ErrorCode.LEASE_REJECTED);
                    continue;
                }
                boolean saved = store.saveProgress(
                        claim, page.nextCursor(), page.caughtUp(), timeProvider.now(),
                        properties.getLeaseDuration());
                if (!saved) {
                    interrupted++;
                    observation.complete(
                            OperationalTelemetry.Outcome.DEGRADED,
                            OperationalTelemetry.ErrorCode.FENCED);
                } else if (page.caughtUp()) {
                    caughtUp++;
                    observation.succeed();
                } else {
                    progressed++;
                    observation.succeed();
                }
            } catch (RuntimeException failure) {
                store.interrupt(claim, timeProvider.now());
                interrupted++;
                observation.fail(OperationalTelemetry.ErrorCode.INTERNAL);
                LOGGER.warn("Projection replay page failed for {} generation {}",
                        claim.generationKey().projectionName().value(),
                        claim.generationKey().generation().value(), failure);
            }
        }
        int cleaned = store.cleanupDue(
                timeProvider.now(), properties.getRetention(), properties.getClaimLimit());
        return new ProjectionSupervisorRunResult(
                claims.size(), progressed, caughtUp, interrupted, cleaned);
    }

    private static OperationalTelemetry.ProjectionName metricProjection(String value) {
        return switch (value) {
            case "team-activity" -> OperationalTelemetry.ProjectionName.TEAM_ACTIVITY;
            case "member-inbox" -> OperationalTelemetry.ProjectionName.MEMBER_INBOX;
            default -> OperationalTelemetry.ProjectionName.OTHER;
        };
    }

    public int recoverStartup() {
        return store.recoverExpired(timeProvider.now());
    }

    public int interruptForShutdown() {
        return store.interruptOwned(properties.getInstanceId(), timeProvider.now());
    }

    public ProjectionSupervisorSummary summary() {
        return store.summary(timeProvider.now(), properties.getRetention());
    }

    public record ProjectionSupervisorRunResult(
            int claimed, int progressed, int caughtUp, int interrupted, int cleaned) {
        public ProjectionSupervisorRunResult {
            if (claimed < 0 || progressed < 0 || caughtUp < 0 || interrupted < 0 || cleaned < 0
                    || progressed + caughtUp + interrupted > claimed) {
                throw new IllegalArgumentException("Projection Supervisor result counters are invalid");
            }
        }
    }
}
