package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.application.artifact.ArtifactDescriptor;
import io.crewscope.application.artifact.ArtifactStore;
import io.crewscope.domain.coding.EvidenceArtifactKind;
import io.crewscope.domain.coding.EvidenceArtifactReference;
import io.crewscope.domain.coding.EvidenceSequence;
import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.task.RuntimeContentHash;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Writes one immutable, integrity-checked command log through the generic ArtifactStore Port. */
final class CommandLogArtifactWriter {

    private static final String CONTENT_TYPE = "text/plain;charset=utf-8";

    private final CodingArtifactPublisher publisher;

    CommandLogArtifactWriter(CodingArtifactPublisher publisher) {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
    }

    CommandLogArtifactWriter(ArtifactStore artifactStore) {
        this(new CodingArtifactPublisher(artifactStore, new CodingArtifactProperties()));
    }

    EvidenceArtifactReference write(
            ExecutionWorkspace workspace,
            Principal actor,
            EvidenceSequence sequence,
            SandboxCommandExecution execution) {
        byte[] content = logContent(execution).getBytes(StandardCharsets.UTF_8);
        ArtifactDescriptor descriptor = publisher.publish(
                CodingArtifactIds.commandLog(workspace.id(), sequence),
                workspace,
                actor,
                CONTENT_TYPE,
                content);
        return new EvidenceArtifactReference(
                descriptor.artifactId(),
                EvidenceArtifactKind.COMMAND_LOG,
                descriptor.contentType(),
                descriptor.size(),
                new RuntimeContentHash(descriptor.sha256().toString()));
    }

    private static String logContent(SandboxCommandExecution execution) {
        return "crewscope-command-log-v1\n"
                + "termination=" + execution.termination().name() + "\n"
                + "exitCode=" + execution.exitCode().map(String::valueOf).orElse("") + "\n"
                + "outputTruncated=" + execution.outputTruncated() + "\n"
                + "--- stdout ---\n"
                + execution.stdout()
                + (execution.stdout().endsWith("\n") ? "" : "\n")
                + "--- stderr ---\n"
                + execution.stderr()
                + (execution.stderr().endsWith("\n") ? "" : "\n");
    }

}
