package io.crewscope.infrastructure.artifact;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.application.artifact.ArtifactAccessContext;
import io.crewscope.application.artifact.ArtifactByteRange;
import io.crewscope.application.artifact.ArtifactContentRange;
import io.crewscope.application.artifact.ArtifactDataClassification;
import io.crewscope.application.artifact.ArtifactProducer;
import io.crewscope.application.artifact.ArtifactScope;
import io.crewscope.application.artifact.ArtifactStoreError;
import io.crewscope.application.artifact.ArtifactStoreException;
import io.crewscope.application.artifact.ArtifactVisibility;
import io.crewscope.application.artifact.ArtifactWriteRequest;
import io.crewscope.application.artifact.Sha256Hash;
import io.crewscope.domain.shared.id.ArtifactId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** Real filesystem Range, full-integrity and interrupted-publication coverage for M4-I09. */
class FilesystemArtifactRangeM4I09Test {

    private static final Instant NOW = Instant.parse("2026-08-19T01:00:00Z");
    private static final byte[] CONTENT = "0123456789-secret-tail".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path root;

    private OrganizationId organizationId;
    private TeamId teamId;
    private WorkspaceId workspaceId;
    private PrincipalId principalId;
    private FilesystemArtifactStore store;

    @BeforeEach
    void setUp() {
        organizationId = OrganizationId.generate();
        teamId = TeamId.generate();
        workspaceId = WorkspaceId.generate();
        principalId = PrincipalId.generate();
        store = new FilesystemArtifactStore(
                root,
                JsonMapper.builder().findAndAddModules().build(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void returnsExactRangeAfterVerifyingTheCompleteBlob() throws Exception {
        ArtifactWriteRequest request = request(ArtifactId.generate(), CONTENT);
        var descriptor = store.put(request, new ByteArrayInputStream(CONTENT));

        try (ArtifactContentRange range = store
                .getRange(request.artifactId(), access(), new ArtifactByteRange(2, 8))
                .orElseThrow()) {
            assertEquals(CONTENT.length, range.descriptor().size());
            assertEquals(6, range.contentLength());
            assertArrayEquals("234567".getBytes(StandardCharsets.UTF_8),
                    range.stream().readAllBytes());
        }

        Files.writeString(
                Path.of(descriptor.storageUri()),
                "X",
                StandardOpenOption.WRITE);
        assertEquals(
                ArtifactStoreError.INTEGRITY_VIOLATION,
                assertThrows(
                                ArtifactStoreException.class,
                                () -> store.getRange(
                                        request.artifactId(),
                                        access(),
                                        new ArtifactByteRange(0, 1)))
                        .error());
    }

    @Test
    void interruptedWritePublishesNoReferenceAndLeavesNoTemporaryPart() throws Exception {
        ArtifactWriteRequest request = request(ArtifactId.generate(), CONTENT);
        InputStream interrupted = new InputStream() {
            private int offset;

            @Override
            public int read() throws IOException {
                if (offset >= 5) {
                    throw new IOException("injected interruption");
                }
                return CONTENT[offset++];
            }
        };

        assertEquals(
                ArtifactStoreError.STORAGE_FAILURE,
                assertThrows(ArtifactStoreException.class, () -> store.put(request, interrupted))
                        .error());
        assertFalse(store.head(request.artifactId(), access()).isPresent());
        try (var files = Files.walk(root.resolve("temporary"))) {
            assertEquals(0, files.filter(Files::isRegularFile).count());
        }
    }

    private ArtifactWriteRequest request(ArtifactId id, byte[] content) {
        return new ArtifactWriteRequest(
                id,
                ArtifactScope.workspace(organizationId, Optional.of(teamId), workspaceId),
                "text/plain;charset=utf-8",
                content.length,
                Sha256Hash.digest(content),
                ArtifactDataClassification.RESTRICTED,
                ArtifactVisibility.WORKSPACE,
                Optional.empty(),
                ArtifactProducer.principal(principalId));
    }

    private ArtifactAccessContext access() {
        return new ArtifactAccessContext(
                organizationId, principalId, Set.of(teamId), Set.of(workspaceId));
    }
}
