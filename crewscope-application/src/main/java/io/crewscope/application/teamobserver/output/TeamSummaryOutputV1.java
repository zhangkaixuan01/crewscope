package io.crewscope.application.teamobserver.output;

import io.crewscope.domain.teamobserver.TeamSummaryRequest;
import java.util.List;
import java.util.Objects;

/** Exact five-section structured output returned by `team-observer@1`. */
public record TeamSummaryOutputV1(
        List<TeamSummaryOutputEntryV1> progress,
        List<TeamSummaryOutputEntryV1> blockers,
        List<TeamSummaryOutputEntryV1> reviewBacklog,
        List<TeamSummaryOutputEntryV1> pendingConfirmations,
        List<TeamSummaryOutputEntryV1> anomalies) {

    public TeamSummaryOutputV1 {
        progress = requireEntries(progress, "progress");
        blockers = requireEntries(blockers, "blockers");
        reviewBacklog = requireEntries(reviewBacklog, "reviewBacklog");
        pendingConfirmations = requireEntries(pendingConfirmations, "pendingConfirmations");
        anomalies = requireEntries(anomalies, "anomalies");
    }

    private static List<TeamSummaryOutputEntryV1> requireEntries(
            List<TeamSummaryOutputEntryV1> values, String field) {
        List<TeamSummaryOutputEntryV1> required = List.copyOf(Objects.requireNonNull(values, field));
        if (required.size() > TeamSummaryRequest.MAX_ITEMS_PER_SECTION
                || required.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(field + " exceeds the fixed section limit");
        }
        return required;
    }
}
