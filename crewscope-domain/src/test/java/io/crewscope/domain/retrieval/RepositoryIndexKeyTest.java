package io.crewscope.domain.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.crewscope.domain.coding.RepositoryBindingId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import org.junit.jupiter.api.Test;

class RepositoryIndexKeyTest {

    private static final String COMMIT_40 = "0123456789abcdef0123456789abcdef01234567";
    private static final String OTHER_COMMIT_40 = "fedcba9876543210fedcba9876543210fedcba98";

    private static RepositoryIndexKey baseline() {
        return new RepositoryIndexKey(
                new OrganizationId(java.util.UUID.nameUUIDFromBytes("org-a".getBytes())),
                new TeamId(java.util.UUID.nameUUIDFromBytes("team-a".getBytes())),
                new RepositoryBindingId(java.util.UUID.nameUUIDFromBytes("binding-a".getBytes())),
                new SourceCommit(COMMIT_40),
                ChunkingPolicyHash.sha256("policy-v1"),
                new EmbeddingModelRevision("text-embedding-v4", 1024, 1L));
    }

    @Test
    void everyComponentParticipatesInIdentity() {
        RepositoryIndexKey baseline = baseline();

        assertNotEquals(baseline, new RepositoryIndexKey(
                new OrganizationId(java.util.UUID.nameUUIDFromBytes("org-b".getBytes())),
                baseline.teamId(), baseline.repositoryBindingId(), baseline.sourceCommit(),
                baseline.chunkingPolicyHash(), baseline.embeddingModelRevision()));

        assertNotEquals(baseline, new RepositoryIndexKey(
                baseline.organizationId(),
                new TeamId(java.util.UUID.nameUUIDFromBytes("team-b".getBytes())),
                baseline.repositoryBindingId(), baseline.sourceCommit(),
                baseline.chunkingPolicyHash(), baseline.embeddingModelRevision()));

        assertNotEquals(baseline, new RepositoryIndexKey(
                baseline.organizationId(), baseline.teamId(),
                RepositoryBindingId.generate(), baseline.sourceCommit(),
                baseline.chunkingPolicyHash(), baseline.embeddingModelRevision()));

        assertNotEquals(baseline, new RepositoryIndexKey(
                baseline.organizationId(), baseline.teamId(), baseline.repositoryBindingId(),
                new SourceCommit(OTHER_COMMIT_40), baseline.chunkingPolicyHash(),
                baseline.embeddingModelRevision()));

        assertNotEquals(baseline, new RepositoryIndexKey(
                baseline.organizationId(), baseline.teamId(), baseline.repositoryBindingId(),
                baseline.sourceCommit(), ChunkingPolicyHash.sha256("policy-v2"),
                baseline.embeddingModelRevision()));

        assertNotEquals(baseline, new RepositoryIndexKey(
                baseline.organizationId(), baseline.teamId(), baseline.repositoryBindingId(),
                baseline.sourceCommit(), baseline.chunkingPolicyHash(),
                new EmbeddingModelRevision("text-embedding-v4", 768, 1L)));
        assertNotEquals(baseline, new RepositoryIndexKey(
                baseline.organizationId(), baseline.teamId(), baseline.repositoryBindingId(),
                baseline.sourceCommit(), baseline.chunkingPolicyHash(),
                new EmbeddingModelRevision("text-embedding-v3", 1024, 1L)));
        assertNotEquals(baseline, new RepositoryIndexKey(
                baseline.organizationId(), baseline.teamId(), baseline.repositoryBindingId(),
                baseline.sourceCommit(), baseline.chunkingPolicyHash(),
                new EmbeddingModelRevision("text-embedding-v4", 1024, 2L)));
    }

    @Test
    void canonicalFormIsDeterministicAndMatchesToString() {
        RepositoryIndexKey key = baseline();

        assertEquals(key.canonical(), baseline().canonical());
        assertEquals(key.canonical(), key.toString());

        RepositoryIndexKey sixtyFourBitCommit = new RepositoryIndexKey(
                key.organizationId(), key.teamId(), key.repositoryBindingId(),
                new SourceCommit(COMMIT_40 + COMMIT_40.substring(0, 24)), key.chunkingPolicyHash(),
                key.embeddingModelRevision());
        assertNotEquals(key.canonical(), sixtyFourBitCommit.canonical());
    }
}
