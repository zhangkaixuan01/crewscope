package io.crewscope.application.retrieval;

import io.crewscope.domain.retrieval.ManifestSourceKey;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskExecutionId;
import java.util.Objects;

/**
 * One member's "not applicable" judgement of one injected source key (M10-I02c). The
 * row is immutable and keyed by (execution, source key, member): a replay converges
 * on the stored row instead of writing a second opinion, and no feedback ever
 * rewrites the sealed manifest or the prompt that was already sent.
 */
public record InjectionReferenceFeedback(
        TaskExecutionId executionId,
        ManifestSourceKey source,
        PrincipalId memberPrincipalId,
        InjectionFeedbackKind kind,
        UtcTimestamp createdAt) {

    public InjectionReferenceFeedback {
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(memberPrincipalId, "memberPrincipalId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
