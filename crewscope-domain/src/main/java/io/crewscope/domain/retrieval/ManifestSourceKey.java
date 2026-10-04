package io.crewscope.domain.retrieval;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The stage-free identity of one manifest reference: source type, source id, source
 * version and content hash. Feedback ("this evidence was not applicable") and claimed
 * references (the model's own receipt of what it says it used) both address evidence
 * by this key alone — the stage lives in the sealed manifest, not in the judgement
 * about it (M10-I02c; see {@link ManifestSourceStage} for the stage half).
 */
public record ManifestSourceKey(
        ManifestSourceType type,
        String sourceId,
        long version,
        String contentHash) {

    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public ManifestSourceKey {
        Objects.requireNonNull(type, "type");
        if (sourceId == null || sourceId.isBlank()) {
            throw new DomainValidationException(
                    "manifestSourceKey.sourceId", "must not be blank");
        }
        sourceId = sourceId.strip();
        if (version < 1) {
            throw new DomainValidationException(
                    "manifestSourceKey.version", "must be positive");
        }
        if (contentHash == null
                || !SHA_256.matcher(contentHash.strip().toLowerCase(Locale.ROOT)).matches()) {
            throw new DomainValidationException(
                    "manifestSourceKey.contentHash", "must be a 64-character lowercase SHA-256 value");
        }
        contentHash = contentHash.strip().toLowerCase(Locale.ROOT);
    }

    /** The key of a reference without its stage — feedback and claims reuse this. */
    public static ManifestSourceKey of(ManifestSourceRef reference) {
        return new ManifestSourceKey(
                reference.type(), reference.sourceId(), reference.version(),
                reference.contentHash());
    }
}
