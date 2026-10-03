package io.crewscope.domain.retrieval.chunking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The policy is the identity of a generation: canonical form is frozen, every knob moves
 * the hash, and the exclusion list carries the secret-shape patterns of S01 §3.3.
 */
class ChunkingPolicyTest {

    @Test
    void canonicalFormIsFrozenAndDeterministic() {
        String canonical = ChunkingPolicy.defaults().canonical();

        assertEquals(canonical, ChunkingPolicy.defaults().canonical());
        assertTrue(canonical.startsWith("chunking-policy-v1\nwindow=80\nstep=70"));
        assertTrue(canonical.contains("\nsnap-search=10\nsnap-floor=20\nmax-file-bytes=1048576"));
        assertTrue(canonical.contains("\nexclude=.env*"));
        assertTrue(canonical.endsWith(".dll"), "the last default exclusion closes the form");
    }

    @Test
    void everyKnobChangesThePolicyHash() {
        var baseline = ChunkingPolicy.defaults().policyHash();

        assertNotEquals(baseline, new ChunkingPolicy(81, 70, 10, 20,
                ChunkingPolicy.DEFAULT_MAX_FILE_BYTES, ChunkingPolicy.DEFAULT_EXCLUDES).policyHash());
        assertNotEquals(baseline, new ChunkingPolicy(80, 69, 10, 20,
                ChunkingPolicy.DEFAULT_MAX_FILE_BYTES, ChunkingPolicy.DEFAULT_EXCLUDES).policyHash());
        assertNotEquals(baseline, new ChunkingPolicy(80, 70, 9, 20,
                ChunkingPolicy.DEFAULT_MAX_FILE_BYTES, ChunkingPolicy.DEFAULT_EXCLUDES).policyHash());
        assertNotEquals(baseline, new ChunkingPolicy(80, 70, 10, 21,
                ChunkingPolicy.DEFAULT_MAX_FILE_BYTES, ChunkingPolicy.DEFAULT_EXCLUDES).policyHash());
        assertNotEquals(baseline, new ChunkingPolicy(80, 70, 10, 20,
                ChunkingPolicy.DEFAULT_MAX_FILE_BYTES + 1, ChunkingPolicy.DEFAULT_EXCLUDES).policyHash());
        var oneMoreExclude = new java.util.ArrayList<>(ChunkingPolicy.DEFAULT_EXCLUDES);
        oneMoreExclude.add("*.secret");
        assertNotEquals(baseline, new ChunkingPolicy(80, 70, 10, 20,
                ChunkingPolicy.DEFAULT_MAX_FILE_BYTES, oneMoreExclude).policyHash());
    }

    @Test
    void excludesMatchTheFrozenSecretAndArtifactPatterns() {
        ChunkingPolicy policy = ChunkingPolicy.defaults();

        assertTrue(policy.excludes(".env.local", 10));
        assertTrue(policy.excludes("deploy/.env", 10), "bare patterns match nested paths too");
        assertTrue(policy.excludes("certs/server.pem", 10));
        assertTrue(policy.excludes(".ssh/id_rsa", 10));
        assertTrue(policy.excludes("config/prod.key", 10));
        assertTrue(policy.excludes("credentials.yaml", 10));
        assertTrue(policy.excludes("node_modules/react/index.js", 10));
        assertTrue(policy.excludes("web/dist/bundle.js", 10));
        assertTrue(policy.excludes("logo.png", 10));
        assertTrue(policy.excludes("src/Main.java", ChunkingPolicy.DEFAULT_MAX_FILE_BYTES + 1),
                "files above the size ceiling are excluded, never truncated");
        assertFalse(policy.excludes("src/main/java/App.java", 2048));
    }

    @Test
    void rejectsInvalidTuning() {
        assertThrows(DomainValidationException.class,
                () -> new ChunkingPolicy(80, 81, 10, 20,
                        ChunkingPolicy.DEFAULT_MAX_FILE_BYTES, List.of()));
        assertThrows(DomainValidationException.class,
                () -> new ChunkingPolicy(80, 70, 11, 20,
                        ChunkingPolicy.DEFAULT_MAX_FILE_BYTES, List.of()),
                "the snap search cannot exceed the overlap");
        assertThrows(DomainValidationException.class,
                () -> new ChunkingPolicy(80, 70, 10, 80,
                        ChunkingPolicy.DEFAULT_MAX_FILE_BYTES, List.of()));
    }
}
