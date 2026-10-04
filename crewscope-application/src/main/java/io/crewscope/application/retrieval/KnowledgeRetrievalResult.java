package io.crewscope.application.retrieval;

import io.crewscope.domain.retrieval.DegradationReasonCode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The unified retrieval outcome (M10-A01): the explainably ordered candidates and the
 * explicit degradation codes. An empty candidate list with no degradation means the
 * search genuinely found nothing above zero — an empty list with a code means a source
 * was skipped and must not be presented as "searched and found nothing" (S01 §3.4).
 */
public record KnowledgeRetrievalResult(
        List<RetrievalCandidate> candidates, List<DegradationReasonCode> degradations) {

    public KnowledgeRetrievalResult {
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
        degradations = List.copyOf(Objects.requireNonNull(degradations, "degradations"));
        for (int index = 0; index < candidates.size(); index++) {
            if (candidates.get(index).rank() != index + 1) {
                throw new IllegalArgumentException(
                        "candidate ranks must be consecutive from 1 in list order");
            }
        }
        Set<DegradationReasonCode> unique = new LinkedHashSet<>(degradations);
        if (unique.size() != degradations.size()) {
            throw new IllegalArgumentException("degradation codes must not repeat");
        }
    }
}
