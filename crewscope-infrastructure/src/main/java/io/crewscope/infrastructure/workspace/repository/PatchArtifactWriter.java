package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.application.artifact.ArtifactDescriptor;
import io.crewscope.application.artifact.ArtifactStore;
import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.coding.PatchArtifactReference;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.task.RuntimeContentHash;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Writes the complete final Patch before its immutable DiffArtifact metadata is published. */
final class PatchArtifactWriter {

    private final CodingArtifactPublisher publisher;

    PatchArtifactWriter(CodingArtifactPublisher publisher) {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
    }

    PatchArtifactWriter(ArtifactStore artifactStore) {
        this(new CodingArtifactPublisher(artifactStore, new CodingArtifactProperties()));
    }

    PatchArtifactReference write(
            ExecutionWorkspace workspace, Principal actor, WorkspaceDiffSnapshot snapshot) {
        byte[] content = Objects.requireNonNull(snapshot, "snapshot")
                .fullPatch()
                .getBytes(StandardCharsets.UTF_8);
        ArtifactDescriptor descriptor = publisher.publish(
                CodingArtifactIds.patch(workspace.id()),
                workspace,
                actor,
                PatchArtifactReference.CONTENT_TYPE,
                content);
        return new PatchArtifactReference(
                descriptor.artifactId(),
                descriptor.size(),
                new RuntimeContentHash(descriptor.sha256().toString()));
    }
}
