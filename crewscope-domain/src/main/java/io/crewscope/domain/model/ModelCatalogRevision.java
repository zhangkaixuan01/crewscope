package io.crewscope.domain.model;

import io.crewscope.domain.shared.error.DomainValidationException;

/** Monotonic CrewScope revision of one stable model catalog entry. */
public record ModelCatalogRevision(long value) implements Comparable<ModelCatalogRevision> {

    public ModelCatalogRevision {
        if (value < 1) {
            throw new DomainValidationException(
                    "modelCatalog.catalogRevision", "must be positive");
        }
    }

    public ModelCatalogRevision next() {
        if (value == Long.MAX_VALUE) {
            throw new DomainValidationException(
                    "modelCatalog.catalogRevision", "must not overflow");
        }
        return new ModelCatalogRevision(value + 1);
    }

    @Override
    public int compareTo(ModelCatalogRevision other) {
        return Long.compare(value, other.value);
    }

    @Override
    public String toString() {
        return Long.toString(value);
    }
}
