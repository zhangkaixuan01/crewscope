package io.crewscope.domain.model;

import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;

/** One immutable append-only price point for an exact model catalog revision. */
public final class ModelPriceRevision {

    private final ModelCatalogCoordinate catalogCoordinate;
    private final long revision;
    private final UtcTimestamp effectiveFrom;
    private final ModelTokenPrice tokenPrice;
    private final ModelPriceSource source;
    private final ModelRegistryHash contentHash;
    private final AuditMetadata audit;

    private ModelPriceRevision(
            ModelCatalogCoordinate catalogCoordinate,
            long revision,
            UtcTimestamp effectiveFrom,
            ModelTokenPrice tokenPrice,
            ModelPriceSource source,
            ModelRegistryHash expectedContentHash,
            AuditMetadata audit) {
        this.catalogCoordinate = Objects.requireNonNull(
                catalogCoordinate, "catalogCoordinate");
        this.revision = requireRevision(revision);
        this.effectiveFrom = Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        this.tokenPrice = Objects.requireNonNull(tokenPrice, "tokenPrice");
        this.source = Objects.requireNonNull(source, "source");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.contentHash = calculateContentHash();
        if (expectedContentHash != null && !expectedContentHash.equals(this.contentHash)) {
            throw new DomainValidationException(
                    "modelPrice.contentHash", "must match the canonical price revision");
        }
    }

    public static ModelPriceRevision publish(
            ModelCatalogCoordinate catalogCoordinate,
            long revision,
            UtcTimestamp effectiveFrom,
            ModelTokenPrice tokenPrice,
            ModelPriceSource source,
            PrincipalId actor,
            UtcTimestamp occurredAt) {
        return new ModelPriceRevision(
                catalogCoordinate,
                revision,
                effectiveFrom,
                tokenPrice,
                source,
                null,
                AuditMetadata.createdBy(actor, occurredAt));
    }

    public static ModelPriceRevision reconstitute(
            ModelCatalogCoordinate catalogCoordinate,
            long revision,
            UtcTimestamp effectiveFrom,
            ModelTokenPrice tokenPrice,
            ModelPriceSource source,
            ModelRegistryHash contentHash,
            AuditMetadata audit) {
        return new ModelPriceRevision(
                catalogCoordinate,
                revision,
                effectiveFrom,
                tokenPrice,
                source,
                Objects.requireNonNull(contentHash, "contentHash"),
                audit);
    }

    private ModelRegistryHash calculateContentHash() {
        StringBuilder canonical = new StringBuilder("model-price-revision-v1");
        ModelRegistryHash.append(canonical, catalogCoordinate.entryId().toString());
        ModelRegistryHash.append(canonical, catalogCoordinate.providerKey().toString());
        ModelRegistryHash.append(canonical, catalogCoordinate.modelId().toString());
        ModelRegistryHash.append(
                canonical, catalogCoordinate.catalogRevision().toString());
        ModelRegistryHash.append(canonical, Long.toString(revision));
        ModelRegistryHash.append(canonical, effectiveFrom.toString());
        ModelRegistryHash.append(canonical, tokenPrice.canonicalValue());
        ModelRegistryHash.append(canonical, source.toString());
        return ModelRegistryHash.sha256(canonical.toString());
    }

    private static long requireRevision(long value) {
        if (value < 1) {
            throw new DomainValidationException("modelPrice.revision", "must be positive");
        }
        return value;
    }

    public ModelCatalogCoordinate catalogCoordinate() {
        return catalogCoordinate;
    }

    public long revision() {
        return revision;
    }

    public UtcTimestamp effectiveFrom() {
        return effectiveFrom;
    }

    public ModelTokenPrice tokenPrice() {
        return tokenPrice;
    }

    public ModelPriceSource source() {
        return source;
    }

    public ModelRegistryHash contentHash() {
        return contentHash;
    }

    public AuditMetadata audit() {
        return audit;
    }
}
