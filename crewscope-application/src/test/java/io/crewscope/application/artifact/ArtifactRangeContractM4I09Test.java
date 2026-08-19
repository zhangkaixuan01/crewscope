package io.crewscope.application.artifact;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.shared.id.ArtifactId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** Range shape, ownership and exact stream-boundary contract introduced by M4-I09. */
class ArtifactRangeContractM4I09Test {

    @Test
    void exposesOnlyTheExactRequestedBytesAndClosesTheOwnedSource() throws Exception {
        AtomicBoolean closed = new AtomicBoolean();
        byte[] bytes = "0123456789".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ArtifactContent content = new ArtifactContent(
                descriptor(bytes),
                new ByteArrayInputStream(bytes) {
                    @Override
                    public void close() throws IOException {
                        closed.set(true);
                        super.close();
                    }
                });

        try (ArtifactContentRange range = ArtifactContentRange.slice(
                content, new ArtifactByteRange(2, 7))) {
            assertEquals(5, range.contentLength());
            assertArrayEquals("23456".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    range.stream().readAllBytes());
            assertEquals(-1, range.stream().read());
        }

        assertTrue(closed.get());
    }

    @Test
    void rejectsEmptyOverflowingAndUnsatisfiedRanges() {
        assertThrows(IllegalArgumentException.class, () -> new ArtifactByteRange(1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ArtifactByteRange(-1, 1));
        assertThrows(
                ArtifactStoreException.class,
                () -> new ArtifactByteRange(8, 11).requireWithin(10));
        assertEquals(ArtifactStoreError.RANGE_NOT_SATISFIABLE,
                assertThrows(
                                ArtifactStoreException.class,
                                () -> new ArtifactByteRange(10, 11).requireWithin(10))
                        .error());
    }

    private static ArtifactDescriptor descriptor(byte[] content) {
        ArtifactId artifactId = ArtifactId.generate();
        OrganizationId organizationId = OrganizationId.generate();
        PrincipalId principalId = PrincipalId.generate();
        return new ArtifactDescriptor(
                artifactId,
                ArtifactScope.organization(organizationId),
                "application/octet-stream",
                content.length,
                Sha256Hash.digest(content),
                ArtifactDataClassification.INTERNAL,
                ArtifactVisibility.ORGANIZATION,
                URI.create("memory:/" + artifactId),
                ArtifactEncryption.NONE,
                ArtifactProducer.principal(principalId),
                UtcTimestamp.parse("2026-08-19T00:00:00Z"),
                Optional.empty(),
                Optional.empty());
    }
}
