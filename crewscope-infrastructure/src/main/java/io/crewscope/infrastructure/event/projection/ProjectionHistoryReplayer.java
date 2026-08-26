package io.crewscope.infrastructure.event.projection;

import io.crewscope.domain.projection.ProjectionGenerationLease;
import java.util.Objects;
import java.util.Optional;

/** Replays one bounded history page so a restarted Supervisor can resume from a durable cursor. */
public final class ProjectionHistoryReplayer {

    private final JdbcProjectionEventHistoryStore historyStore;
    private final GenerationAwareProjectionRunner runner;

    public ProjectionHistoryReplayer(
            JdbcProjectionEventHistoryStore historyStore,
            GenerationAwareProjectionRunner runner) {
        this.historyStore = Objects.requireNonNull(historyStore, "historyStore");
        this.runner = Objects.requireNonNull(runner, "runner");
    }

    public ProjectionReplayBatchResult replayPage(
            ProjectionGenerationLease lease,
            Optional<ProjectionHistoryCursor> after,
            int pageSize) {
        ProjectionGenerationLease target = Objects.requireNonNull(lease, "lease");
        ProjectionHistoryPage page = historyStore.read(
                target.key().organizationId(),
                Objects.requireNonNull(after, "after"),
                pageSize);
        int applied = 0;
        int duplicates = 0;
        for (ProjectionHistoryEvent event : page.events()) {
            ProjectionConsumptionResult result = runner.consume(target, event.publication());
            if (result == ProjectionConsumptionResult.LEASE_REJECTED) {
                return new ProjectionReplayBatchResult(
                        page.events().size(), applied, duplicates, true, after);
            }
            if (result == ProjectionConsumptionResult.APPLIED) {
                applied++;
            } else {
                duplicates++;
            }
        }
        return new ProjectionReplayBatchResult(
                page.events().size(),
                applied,
                duplicates,
                false,
                page.events().isEmpty() ? after : page.nextCursor());
    }
}
