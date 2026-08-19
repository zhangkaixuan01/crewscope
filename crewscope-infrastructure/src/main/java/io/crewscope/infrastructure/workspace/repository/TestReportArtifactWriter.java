package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.application.artifact.ArtifactDescriptor;
import io.crewscope.domain.coding.EvidenceArtifactKind;
import io.crewscope.domain.coding.EvidenceArtifactReference;
import io.crewscope.domain.coding.EvidenceSequence;
import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.task.RuntimeContentHash;
import java.util.Objects;

/** Publishes a complete immutable test report before TestEvidence references it. */
public final class TestReportArtifactWriter {

    private final CodingArtifactPublisher publisher;

    TestReportArtifactWriter(CodingArtifactPublisher publisher) {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
    }

    public EvidenceArtifactReference write(
            ExecutionWorkspace workspace,
            Principal actor,
            EvidenceSequence testEvidenceSequence,
            String contentType,
            byte[] report) {
        ArtifactDescriptor descriptor = publisher.publish(
                CodingArtifactIds.testReport(workspace.id(), testEvidenceSequence),
                workspace,
                actor,
                contentType,
                report);
        return new EvidenceArtifactReference(
                descriptor.artifactId(),
                EvidenceArtifactKind.TEST_REPORT,
                descriptor.contentType(),
                descriptor.size(),
                new RuntimeContentHash(descriptor.sha256().toString()));
    }
}
