package io.crewscope.domain.model;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable append-only price schedule for one exact model catalog revision. */
public final class ModelPriceSchedule {

    private final ModelCatalogCoordinate catalogCoordinate;
    private final List<ModelPriceRevision> revisions;
    private final ModelRegistryHash scheduleHash;

    private ModelPriceSchedule(
            ModelCatalogCoordinate catalogCoordinate,
            List<ModelPriceRevision> revisions,
            ModelRegistryHash expectedScheduleHash) {
        this.catalogCoordinate = Objects.requireNonNull(
                catalogCoordinate, "catalogCoordinate");
        this.revisions = validateRevisions(catalogCoordinate, revisions);
        this.scheduleHash = calculateScheduleHash();
        if (expectedScheduleHash != null && !expectedScheduleHash.equals(this.scheduleHash)) {
            throw new DomainValidationException(
                    "modelPrice.scheduleHash", "must match the canonical price schedule");
        }
    }

    /** Starts pricing for one currently selectable catalog revision. */
    public static ModelPriceSchedule start(
            ModelProviderDefinition provider,
            ModelCatalogEntry catalogEntry,
            UtcTimestamp effectiveFrom,
            ModelTokenPrice tokenPrice,
            ModelPriceSource source,
            PrincipalId actor,
            UtcTimestamp occurredAt) {
        ModelCatalogEntry requiredEntry = Objects.requireNonNull(catalogEntry, "catalogEntry");
        requiredEntry.requireSelectable(provider);
        ModelPriceRevision initial = ModelPriceRevision.publish(
                requiredEntry.coordinate(), 1, effectiveFrom, tokenPrice, source, actor, occurredAt);
        return new ModelPriceSchedule(
                requiredEntry.coordinate(), List.of(initial), null);
    }

    /** Appends a later price without rewriting any previous revision or interval boundary. */
    public ModelPriceSchedule append(
            ModelProviderDefinition provider,
            ModelCatalogEntry catalogEntry,
            UtcTimestamp effectiveFrom,
            ModelTokenPrice tokenPrice,
            ModelPriceSource source,
            PrincipalId actor,
            UtcTimestamp occurredAt) {
        ModelCatalogEntry requiredEntry = Objects.requireNonNull(catalogEntry, "catalogEntry");
        requiredEntry.requireSelectable(provider);
        if (!catalogCoordinate.equals(requiredEntry.coordinate())) {
            throw new DomainValidationException(
                    "modelPrice.catalogCoordinate",
                    "must match the exact model catalog revision");
        }
        UtcTimestamp requiredEffectiveFrom = Objects.requireNonNull(
                effectiveFrom, "effectiveFrom");
        ModelPriceRevision latest = latestRevision();
        if (requiredEffectiveFrom.compareTo(latest.effectiveFrom()) <= 0) {
            throw new DomainValidationException(
                    "modelPrice.effectiveFrom",
                    "must be later than the latest append-only price revision");
        }
        if (latest.revision() == Long.MAX_VALUE) {
            throw new DomainValidationException(
                    "modelPrice.revision", "must not overflow");
        }
        List<ModelPriceRevision> next = new ArrayList<>(revisions);
        next.add(ModelPriceRevision.publish(
                catalogCoordinate,
                latest.revision() + 1,
                requiredEffectiveFrom,
                tokenPrice,
                source,
                actor,
                occurredAt));
        return new ModelPriceSchedule(catalogCoordinate, next, null);
    }

    /** Reconstitutes the complete ordered schedule and verifies every revision and hash. */
    public static ModelPriceSchedule reconstitute(
            ModelCatalogCoordinate catalogCoordinate,
            List<ModelPriceRevision> revisions,
            ModelRegistryHash scheduleHash) {
        return new ModelPriceSchedule(
                catalogCoordinate,
                revisions,
                Objects.requireNonNull(scheduleHash, "scheduleHash"));
    }

    /** Rebuilds a schedule when storage keeps every hash-closed revision but no redundant root hash. */
    public static ModelPriceSchedule reconstitute(
            ModelCatalogCoordinate catalogCoordinate, List<ModelPriceRevision> revisions) {
        return new ModelPriceSchedule(catalogCoordinate, revisions, null);
    }

    public Optional<ModelPriceRevision> priceAt(UtcTimestamp timestamp) {
        UtcTimestamp required = Objects.requireNonNull(timestamp, "timestamp");
        ModelPriceRevision selected = null;
        for (ModelPriceRevision revision : revisions) {
            if (revision.effectiveFrom().compareTo(required) > 0) {
                break;
            }
            selected = revision;
        }
        return Optional.ofNullable(selected);
    }

    public List<ModelPriceTimeSlice> timeSlices() {
        List<ModelPriceTimeSlice> slices = new ArrayList<>(revisions.size());
        for (int index = 0; index < revisions.size(); index++) {
            Optional<UtcTimestamp> effectiveUntil = index + 1 < revisions.size()
                    ? Optional.of(revisions.get(index + 1).effectiveFrom())
                    : Optional.empty();
            slices.add(new ModelPriceTimeSlice(revisions.get(index), effectiveUntil));
        }
        return List.copyOf(slices);
    }

    private static List<ModelPriceRevision> validateRevisions(
            ModelCatalogCoordinate catalogCoordinate,
            List<ModelPriceRevision> revisions) {
        List<ModelPriceRevision> required = List.copyOf(
                Objects.requireNonNull(revisions, "revisions"));
        if (required.isEmpty()) {
            throw new DomainValidationException(
                    "modelPrice.revisions", "must not be empty");
        }
        ModelPriceRevision previous = null;
        for (int index = 0; index < required.size(); index++) {
            ModelPriceRevision current = required.get(index);
            if (!catalogCoordinate.equals(current.catalogCoordinate())
                    || current.revision() != index + 1L) {
                throw new DomainValidationException(
                        "modelPrice.revisions",
                        "must use one catalog coordinate and contiguous revisions from one");
            }
            if (previous != null
                    && current.effectiveFrom().compareTo(previous.effectiveFrom()) <= 0) {
                throw new DomainValidationException(
                        "modelPrice.effectiveFrom",
                        "must increase strictly so derived price slices cannot overlap");
            }
            previous = current;
        }
        return required;
    }

    private ModelRegistryHash calculateScheduleHash() {
        StringBuilder canonical = new StringBuilder("model-price-schedule-v1");
        ModelRegistryHash.append(canonical, catalogCoordinate.entryId().toString());
        ModelRegistryHash.append(canonical, catalogCoordinate.providerKey().toString());
        ModelRegistryHash.append(canonical, catalogCoordinate.modelId().toString());
        ModelRegistryHash.append(
                canonical, catalogCoordinate.catalogRevision().toString());
        revisions.forEach(revision ->
                ModelRegistryHash.append(canonical, revision.contentHash().toString()));
        return ModelRegistryHash.sha256(canonical.toString());
    }

    private ModelPriceRevision latestRevision() {
        return revisions.get(revisions.size() - 1);
    }

    public ModelCatalogCoordinate catalogCoordinate() {
        return catalogCoordinate;
    }

    public List<ModelPriceRevision> revisions() {
        return revisions;
    }

    public ModelRegistryHash scheduleHash() {
        return scheduleHash;
    }
}
