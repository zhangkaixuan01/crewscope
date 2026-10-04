package io.crewscope.application.retrieval;

import io.crewscope.domain.retrieval.ManifestSourceKey;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskExecutionId;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * The model's own receipt of which injected evidence it claims to have used, one row
 * per (execution, attempt) (M10-I02c). The constructor normalizes the list to a
 * canonical order with duplicates removed: the model's output order is not a fact,
 * so two receipts with the same set are the same receipt — a replay, not a conflict.
 */
public record InjectionClaimedReferences(
        TaskExecutionId executionId,
        int attempt,
        List<ManifestSourceKey> claimed,
        UtcTimestamp createdAt) {

    private static final Comparator<ManifestSourceKey> CANONICAL_ORDER =
            Comparator.comparing(ManifestSourceKey::type)
                    .thenComparing(ManifestSourceKey::sourceId)
                    .thenComparingLong(ManifestSourceKey::version)
                    .thenComparing(ManifestSourceKey::contentHash);

    public InjectionClaimedReferences {
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(claimed, "claimed");
        Objects.requireNonNull(createdAt, "createdAt");
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be positive");
        }
        claimed = claimed.stream()
                .distinct()
                .sorted(CANONICAL_ORDER)
                .toList();
    }

    /** Set equality on the canonical order: same set of keys is the same receipt. */
    public boolean sameClaimAs(InjectionClaimedReferences other) {
        return other != null && claimed.equals(other.claimed());
    }
}
