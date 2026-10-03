package io.crewscope.infrastructure.persistence.retrieval;

import io.crewscope.domain.retrieval.ChunkingPolicyHash;
import io.crewscope.domain.retrieval.EmbeddingModelRevision;
import io.crewscope.domain.retrieval.RepositoryIndexKey;
import io.crewscope.domain.retrieval.SourceCommit;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.coding.RepositoryBindingId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Persistence codec for {@link RepositoryIndexKey}: the canonical form embeds NUL
 * separators that PostgreSQL text cannot store, so rows carry its SHA-256 digest plus
 * the decomposed six-tuple columns, and the typed key is always reconstituted from
 * those columns — never parsed back out of the digest.
 */
final class IndexKeyCodec {

    private IndexKeyCodec() {
    }

    /** SHA-256 hex digest of the canonical form; the storable identity of one key. */
    static String hash(RepositoryIndexKey key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(key.canonical().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    /** Rebuilds the typed key from persisted columns (dimension is the frozen product dimension). */
    static RepositoryIndexKey reconstitute(
            OrganizationId organizationId,
            TeamId teamId,
            RepositoryBindingId repositoryBindingId,
            String sourceCommit,
            String chunkPolicyHash,
            String modelKey,
            int dimension,
            long modelRevision) {
        return new RepositoryIndexKey(
                organizationId,
                teamId,
                repositoryBindingId,
                new SourceCommit(sourceCommit),
                new ChunkingPolicyHash(chunkPolicyHash),
                new EmbeddingModelRevision(modelKey, dimension, modelRevision));
    }
}
