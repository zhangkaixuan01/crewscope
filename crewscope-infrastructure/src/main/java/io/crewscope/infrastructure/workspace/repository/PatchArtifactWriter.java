package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.application.artifact.ArtifactDescriptor;
import io.crewscope.application.artifact.ArtifactStore;
import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.coding.PatchArtifactReference;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.task.RuntimeContentHash;
import io.crewscope.domain.task.RuntimeArtifactKind;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Writes the complete final Patch before its immutable DiffArtifact metadata is published. */
final class PatchArtifactWriter {

    private final CodingArtifactPublisher publisher;
    private final java.util.Optional<CodingRuntimeArtifactRegistrar> registrar;

    PatchArtifactWriter(CodingArtifactPublisher publisher) {
        this(publisher, java.util.Optional.empty());
    }

    PatchArtifactWriter(
            CodingArtifactPublisher publisher, CodingRuntimeArtifactRegistrar registrar) {
        this(publisher, java.util.Optional.of(Objects.requireNonNull(registrar, "registrar")));
    }

    private PatchArtifactWriter(
            CodingArtifactPublisher publisher,
            java.util.Optional<CodingRuntimeArtifactRegistrar> registrar) {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.registrar = Objects.requireNonNull(registrar, "registrar");
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
        registrar.ifPresent(value -> value.register(
                workspace, actor, RuntimeArtifactKind.DIFF_PATCH, descriptor));
        return new PatchArtifactReference(
                descriptor.artifactId(),
                descriptor.size(),
                new RuntimeContentHash(descriptor.sha256().toString()));
    }
}
