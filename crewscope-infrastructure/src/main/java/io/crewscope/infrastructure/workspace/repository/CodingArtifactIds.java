package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.domain.coding.EvidenceSequence;
import io.crewscope.domain.coding.ExecutionWorkspaceId;
import io.crewscope.domain.shared.id.ArtifactId;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.UUID;

/** Stable IDs make publication retries converge on the same immutable logical Artifact. */
final class CodingArtifactIds {

    private CodingArtifactIds() {}

    static ArtifactId patch(ExecutionWorkspaceId workspaceId) {
        return derive("diff-patch", workspaceId, null);
    }

    static ArtifactId commandLog(
            ExecutionWorkspaceId workspaceId, EvidenceSequence sequence) {
        return derive("command-log", workspaceId, Objects.requireNonNull(sequence, "sequence").value());
    }

    static ArtifactId testReport(
            ExecutionWorkspaceId workspaceId, EvidenceSequence sequence) {
        return derive("test-report", workspaceId, Objects.requireNonNull(sequence, "sequence").value());
    }

    private static ArtifactId derive(
            String purpose, ExecutionWorkspaceId workspaceId, Long sequence) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("crewscope-coding-artifact-v1\0".getBytes(StandardCharsets.UTF_8));
            digest.update(purpose.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(Objects.requireNonNull(workspaceId, "workspaceId")
                    .toString()
                    .getBytes(StandardCharsets.UTF_8));
            if (sequence != null) {
                digest.update((byte) 0);
                digest.update(Long.toString(sequence).getBytes(StandardCharsets.UTF_8));
            }
            byte[] hash = digest.digest();
            // RFC 9562 UUIDv8 marks these application-defined SHA-256 bits as a UUID.
            hash[6] = (byte) ((hash[6] & 0x0f) | 0x80);
            hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
            ByteBuffer bytes = ByteBuffer.wrap(hash);
            return new ArtifactId(new UUID(bytes.getLong(), bytes.getLong()));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable for Coding Artifact identifiers",
                    impossible);
        }
    }
}
