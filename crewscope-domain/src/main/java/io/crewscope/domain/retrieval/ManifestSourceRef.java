package io.crewscope.domain.retrieval;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One manifest reference with its triple provenance: source identity, source version
 * and content hash. The triple is what later audits reconcile against — a reference
 * missing any of the three cannot be attributed (M10-S01 §3.3).
 */
public record ManifestSourceRef(
        ManifestSourceType type,
        String sourceId,
        long version,
        String contentHash,
        ManifestSourceStage stage) {

    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public ManifestSourceRef {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(stage, "stage");
        if (sourceId == null || sourceId.isBlank()) {
            throw new DomainValidationException(
                    "manifestSourceRef.sourceId", "must not be blank");
        }
        sourceId = sourceId.strip();
        if (version < 1) {
            throw new DomainValidationException(
                    "manifestSourceRef.version", "must be positive");
        }
        if (contentHash == null
                || !SHA_256.matcher(contentHash.strip().toLowerCase(Locale.ROOT)).matches()) {
            throw new DomainValidationException(
                    "manifestSourceRef.contentHash", "must be a 64-character lowercase SHA-256 value");
        }
        contentHash = contentHash.strip().toLowerCase(Locale.ROOT);
    }

    public boolean injected() {
        return stage == ManifestSourceStage.INJECTED;
    }
}
