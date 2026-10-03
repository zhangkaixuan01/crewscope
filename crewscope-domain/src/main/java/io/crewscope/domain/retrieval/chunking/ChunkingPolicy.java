package io.crewscope.domain.retrieval.chunking;

import io.crewscope.domain.retrieval.ChunkingPolicyHash;
import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.List;

/**
 * The frozen chunking policy of one index build (M10-S01 §3.3): an ~80-line window with
 * ~10 lines of overlap snapped at blank-line boundaries, a per-file size ceiling and the
 * secret/build-artifact exclusion globs. Every knob participates in the canonical form,
 * so changing any of them changes {@link #policyHash()} and therefore the index key —
 * a rebuild under a different policy is a new generation, never an in-place rewrite.
 */
public record ChunkingPolicy(
        int windowLines,
        int stepLines,
        int snapSearchLines,
        int snapFloorLines,
        long maxFileBytes,
        List<String> excludes) {

    /** S01 §3.3 measured defaults: 80-line window, 70-line step (10 overlap), snap floor 20. */
    public static final int DEFAULT_WINDOW_LINES = 80;
    public static final int DEFAULT_STEP_LINES = 70;
    public static final int DEFAULT_SNAP_SEARCH_LINES = 10;
    public static final int DEFAULT_SNAP_FLOOR_LINES = 20;
    /** One megabyte: files above this are excluded rather than truncated. */
    public static final long DEFAULT_MAX_FILE_BYTES = 1024L * 1024L;

    /** S01 §3.3 exclusion list: secret shapes, VCS/npm internals and build outputs. */
    public static final List<String> DEFAULT_EXCLUDES = List.of(
            ".env*",
            "*.pem",
            "id_rsa*",
            "*.key",
            "credentials*",
            ".git/",
            "node_modules/",
            "target/",
            "build/",
            "dist/",
            "out/",
            "*.png",
            "*.jpg",
            "*.jpeg",
            "*.gif",
            "*.ico",
            "*.pdf",
            "*.zip",
            "*.gz",
            "*.jar",
            "*.class",
            "*.so",
            "*.dylib",
            "*.dll");

    public ChunkingPolicy {
        if (windowLines < 1 || windowLines > 400) {
            throw new DomainValidationException(
                    "chunkingPolicy.windowLines", "must be within [1, 400]");
        }
        if (stepLines < 1 || stepLines > windowLines) {
            throw new DomainValidationException(
                    "chunkingPolicy.stepLines", "must be within [1, windowLines]");
        }
        if (snapSearchLines < 0 || snapSearchLines > windowLines - stepLines) {
            throw new DomainValidationException(
                    "chunkingPolicy.snapSearchLines", "must not exceed the overlap");
        }
        if (snapFloorLines < 0 || snapFloorLines >= windowLines) {
            throw new DomainValidationException(
                    "chunkingPolicy.snapFloorLines", "must be within [0, windowLines)");
        }
        if (maxFileBytes < 1) {
            throw new DomainValidationException(
                    "chunkingPolicy.maxFileBytes", "must be positive");
        }
        excludes = List.copyOf(excludes == null ? List.of() : excludes);
        for (String pattern : excludes) {
            if (pattern == null || pattern.isBlank() || pattern.length() > 256) {
                throw new DomainValidationException(
                        "chunkingPolicy.excludes", "patterns must be non-blank and bounded");
            }
        }
    }

    public static ChunkingPolicy defaults() {
        return new ChunkingPolicy(
                DEFAULT_WINDOW_LINES,
                DEFAULT_STEP_LINES,
                DEFAULT_SNAP_SEARCH_LINES,
                DEFAULT_SNAP_FLOOR_LINES,
                DEFAULT_MAX_FILE_BYTES,
                DEFAULT_EXCLUDES);
    }

    /** Whether one repository file is excluded from chunking entirely (never truncated). */
    public boolean excludes(String path, long sizeBytes) {
        if (sizeBytes > maxFileBytes) {
            return true;
        }
        return ChunkExclusion.matchesAny(excludes, path);
    }

    /**
     * Deterministic serialization of every knob; the input of {@link #policyHash()}. The
     * field order and separators are frozen — editing them invalidates every stored index
     * key, which is exactly why they never change.
     */
    public String canonical() {
        StringBuilder canonical = new StringBuilder("chunking-policy-v1")
                .append("\nwindow=").append(windowLines)
                .append("\nstep=").append(stepLines)
                .append("\nsnap-search=").append(snapSearchLines)
                .append("\nsnap-floor=").append(snapFloorLines)
                .append("\nmax-file-bytes=").append(maxFileBytes);
        for (String pattern : excludes) {
            canonical.append("\nexclude=").append(pattern);
        }
        return canonical.toString();
    }

    public ChunkingPolicyHash policyHash() {
        return ChunkingPolicyHash.sha256(canonical());
    }

    @Override
    public String toString() {
        return "ChunkingPolicy[" + policyHash() + ", window=" + windowLines
                + ", step=" + stepLines + ", excludes=" + excludes.size() + " patterns]";
    }
}
