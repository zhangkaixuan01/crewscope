package io.crewscope.application.coding.query;

import io.crewscope.application.coding.query.CodingAttemptProjection.ArtifactSummary;
import java.time.Instant;
import java.util.UUID;

/** Public-safe command outcome; argv, Sandbox image and physical working directory stay private. */
public record CommandEvidenceProjection(
        UUID id,
        long sequence,
        String commandKind,
        String toolKey,
        int timeoutSeconds,
        Instant startedAt,
        Instant finishedAt,
        String termination,
        Integer exitCode,
        String summary,
        String failureClassification,
        String evidenceHash,
        ArtifactSummary commandLog) {}
