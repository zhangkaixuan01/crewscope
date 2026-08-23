package io.crewscope.domain.review;

import io.crewscope.domain.coding.CommandEvidence;
import io.crewscope.domain.coding.CommandEvidenceReference;
import io.crewscope.domain.coding.CommandKind;
import io.crewscope.domain.coding.CommandTermination;
import io.crewscope.domain.coding.EvidenceSummary;
import java.util.Objects;
import java.util.Optional;

/** Safe bounded command outcome; raw argv, environment and logs remain outside Reviewer context. */
public record ReviewCommandEvidenceReference(
        CommandEvidenceReference evidence,
        CommandKind commandKind,
        CommandTermination termination,
        Optional<Integer> exitCode,
        EvidenceSummary summary) {

    public ReviewCommandEvidenceReference {
        evidence = Objects.requireNonNull(evidence, "evidence");
        commandKind = Objects.requireNonNull(commandKind, "commandKind");
        termination = Objects.requireNonNull(termination, "termination");
        exitCode = Objects.requireNonNull(exitCode, "exitCode");
        summary = Objects.requireNonNull(summary, "summary");
    }

    public static ReviewCommandEvidenceReference from(CommandEvidence evidence) {
        CommandEvidence required = Objects.requireNonNull(evidence, "evidence");
        return new ReviewCommandEvidenceReference(
                required.reference(),
                required.commandSpec().commandKind(),
                required.termination(),
                required.exitCode(),
                required.summary());
    }
}
