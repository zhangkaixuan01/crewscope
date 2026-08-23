package io.crewscope.domain.action;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;

/** Proof that no external write occurred and this exact action may become READY again. */
public record ActionRetryDirective(
        ActionEvidenceReference noSideEffectEvidence, UtcTimestamp notBefore) {

    public ActionRetryDirective {
        noSideEffectEvidence = Objects.requireNonNull(
                noSideEffectEvidence, "noSideEffectEvidence");
        notBefore = Objects.requireNonNull(notBefore, "notBefore");
        if (!noSideEffectEvidence.code().startsWith("NO_SIDE_EFFECT_")) {
            throw new DomainValidationException(
                    "actionRetry.evidence", "must explicitly prove that no side effect occurred");
        }
    }
}
