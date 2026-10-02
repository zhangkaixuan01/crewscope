package io.crewscope.domain.retrieval;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskExecutionId;
import java.util.List;
import java.util.Objects;

/**
 * Sealed record of what one prompt assembly attempt considered, injected, trimmed and
 * degraded. Idempotency key is (executionId, attempt): appending the same pair twice
 * must not create a second manifest (M10-S01 §3.3).
 */
public record InjectionManifest(
        InjectionManifestId id,
        TaskExecutionId executionId,
        int attempt,
        List<ManifestSourceRef> references,
        List<TrimRecord> trims,
        PromptBudgetSnapshot budget,
        List<DegradationReasonCode> degradations,
        UtcTimestamp createdAt) {

    public InjectionManifest {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(executionId, "executionId");
        if (attempt < 1) {
            throw new DomainValidationException(
                    "injectionManifest.attempt", "must be positive");
        }
        references = references == null ? List.of() : List.copyOf(references);
        trims = trims == null ? List.of() : List.copyOf(trims);
        Objects.requireNonNull(budget, "budget");
        degradations = degradations == null ? List.of() : List.copyOf(degradations);
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public List<ManifestSourceRef> injectedReferences() {
        return references.stream().filter(ManifestSourceRef::injected).toList();
    }
}
