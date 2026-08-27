package io.crewscope.application.operations;

import io.crewscope.application.projection.ProjectionAdministration;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Builds member-safe summaries and administrator-only diagnostics from one fixed snapshot. */
public final class OperationsHealthService {

    private final WorkItemAccessPolicy accessPolicy;
    private final ProjectionAdministration administration;
    private final OperationsHealthQueryPort queries;
    private final TransactionExecutor transactions;
    private final TimeProvider timeProvider;
    private final OperationsHealthThresholds thresholds;

    public OperationsHealthService(
            WorkItemAccessPolicy accessPolicy,
            ProjectionAdministration administration,
            OperationsHealthQueryPort queries,
            TransactionExecutor transactions,
            TimeProvider timeProvider,
            OperationsHealthThresholds thresholds) {
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
        this.administration = Objects.requireNonNull(administration, "administration");
        this.queries = Objects.requireNonNull(queries, "queries");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
        this.thresholds = Objects.requireNonNull(thresholds, "thresholds");
    }

    public OperationsMemberHealthSummary summary(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId) {
        return transactions.required(() -> {
            accessPolicy.requireVisibleTeam(context, organizationId, teamId);
            return derive(requireSnapshot(organizationId), timeProvider.now());
        });
    }

    public OperationsAdministratorDiagnostics diagnostics(
            OrganizationId organizationId, TeamAccessContext access) {
        return transactions.required(() -> {
            UtcTimestamp now = timeProvider.now();
            administration.requireOrganizationAdministrator(organizationId, access, now);
            OperationsHealthSnapshot snapshot = requireSnapshot(organizationId);
            return new OperationsAdministratorDiagnostics(
                    derive(snapshot, now),
                    snapshot.projections().stream()
                            .sorted(Comparator.comparing(value -> value.projectionName().value()))
                            .toList(),
                    snapshot.recoveryCandidates());
        });
    }

    private OperationsHealthSnapshot requireSnapshot(OrganizationId organizationId) {
        OperationsHealthSnapshot snapshot = Objects.requireNonNull(
                queries.observe(organizationId), "operations health snapshot");
        if (!snapshot.organizationId().equals(organizationId)) {
            throw new IllegalStateException("operations health snapshot has a mismatched scope");
        }
        return snapshot;
    }

    private OperationsMemberHealthSummary derive(
            OperationsHealthSnapshot snapshot, UtcTimestamp now) {
        if (snapshot.observedAt().value().isAfter(now.value())) {
            throw new IllegalStateException("operations health snapshot is from the future");
        }
        List<OperationsComponentSummary> components = snapshot.components().stream()
                .map(observation -> summarize(observation, now))
                .sorted(Comparator.comparingInt(value -> value.component().ordinal()))
                .toList();
        OperationsHealthLevel overall = components.stream()
                .map(OperationsComponentSummary::health)
                .reduce(OperationsHealthLevel.HEALTHY, OperationsHealthLevel::worst);
        return new OperationsMemberHealthSummary(snapshot.observedAt(), overall, components);
    }

    private OperationsComponentSummary summarize(
            OperationsComponentObservation observation, UtcTimestamp now) {
        OperationsComponentThreshold threshold = thresholds.require(observation.component());
        long ageSeconds = observation.oldestOutstandingAt()
                .map(value -> {
                    if (value.value().isAfter(now.value())) {
                        throw new IllegalStateException(
                                "operations outstanding timestamp is from the future");
                    }
                    return Duration.between(value.value(), now.value()).toSeconds();
                })
                .orElse(0L);
        OperationsHealthLevel health;
        if (observation.unavailable()) {
            health = OperationsHealthLevel.UNAVAILABLE;
        } else if (observation.failures() > 0
                || observation.backlog() >= threshold.attentionBacklog()
                || ageSeconds >= threshold.attentionAfter().toSeconds()) {
            health = OperationsHealthLevel.ATTENTION_REQUIRED;
        } else if (observation.backlog() >= threshold.degradedBacklog()
                || ageSeconds >= threshold.degradedAfter().toSeconds()) {
            health = OperationsHealthLevel.DEGRADED;
        } else {
            health = OperationsHealthLevel.HEALTHY;
        }
        return new OperationsComponentSummary(
                observation.component(),
                health,
                observation.backlog(),
                observation.inFlight(),
                observation.failures(),
                observation.affected(),
                ageSeconds,
                ageSeconds >= threshold.degradedAfter().toSeconds());
    }
}
