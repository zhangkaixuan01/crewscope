package io.crewscope.application.review;

import io.crewscope.application.artifact.ArtifactAccessContext;
import io.crewscope.domain.coding.CommandEvidence;
import io.crewscope.domain.coding.DiffArtifact;
import io.crewscope.domain.coding.TestEvidence;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.review.ContextPackage;
import io.crewscope.domain.review.ContextPackageId;
import io.crewscope.domain.review.ReviewSubject;
import io.crewscope.domain.review.ReviewerExecutionReference;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Exact immutable M4 facts used to build one bounded Reviewer context package. */
public record BuildReviewContextPackageRequest(
        ContextPackageId contextPackageId,
        Optional<ContextPackage> predecessor,
        ReviewSubject subject,
        DiffArtifact diffArtifact,
        TestEvidence testEvidence,
        List<CommandEvidence> commandEvidence,
        ReviewerExecutionReference reviewer,
        ArtifactAccessContext artifactAccess,
        Principal actor,
        UtcTimestamp createdAt) {

    public BuildReviewContextPackageRequest {
        contextPackageId = Objects.requireNonNull(contextPackageId, "contextPackageId");
        predecessor = Objects.requireNonNull(predecessor, "predecessor");
        subject = Objects.requireNonNull(subject, "subject");
        diffArtifact = Objects.requireNonNull(diffArtifact, "diffArtifact");
        testEvidence = Objects.requireNonNull(testEvidence, "testEvidence");
        commandEvidence = List.copyOf(Objects.requireNonNull(commandEvidence, "commandEvidence"));
        reviewer = Objects.requireNonNull(reviewer, "reviewer");
        artifactAccess = Objects.requireNonNull(artifactAccess, "artifactAccess");
        actor = Objects.requireNonNull(actor, "actor");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }
}
