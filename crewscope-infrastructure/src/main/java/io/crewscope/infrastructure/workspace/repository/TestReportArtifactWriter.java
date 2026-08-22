package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.application.artifact.ArtifactDescriptor;
import io.crewscope.domain.coding.EvidenceArtifactKind;
import io.crewscope.domain.coding.EvidenceArtifactReference;
import io.crewscope.domain.coding.EvidenceSequence;
import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.task.RuntimeContentHash;
import io.crewscope.domain.task.RuntimeArtifactKind;
import java.util.Objects;

/** Publishes a complete immutable test report before TestEvidence references it. */
public final class TestReportArtifactWriter {

    private final CodingArtifactPublisher publisher;
    private final java.util.Optional<CodingRuntimeArtifactRegistrar> registrar;

    TestReportArtifactWriter(CodingArtifactPublisher publisher) {
        this(publisher, java.util.Optional.empty());
    }

    TestReportArtifactWriter(
            CodingArtifactPublisher publisher, CodingRuntimeArtifactRegistrar registrar) {
        this(publisher, java.util.Optional.of(Objects.requireNonNull(registrar, "registrar")));
    }

    private TestReportArtifactWriter(
            CodingArtifactPublisher publisher,
            java.util.Optional<CodingRuntimeArtifactRegistrar> registrar) {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.registrar = Objects.requireNonNull(registrar, "registrar");
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
        registrar.ifPresent(value -> value.register(
                workspace, actor, RuntimeArtifactKind.TEST_REPORT, descriptor));
        return new EvidenceArtifactReference(
                descriptor.artifactId(),
                EvidenceArtifactKind.TEST_REPORT,
                descriptor.contentType(),
                descriptor.size(),
                new RuntimeContentHash(descriptor.sha256().toString()));
    }
}
