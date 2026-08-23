package io.crewscope.domain.model;

import java.util.Objects;

/** Exact stable model entry and catalog revision coordinate used by runtime and pricing facts. */
public record ModelCatalogCoordinate(
        ModelCatalogEntryId entryId,
        ModelProviderKey providerKey,
        ModelId modelId,
        ModelCatalogRevision catalogRevision) {

    public ModelCatalogCoordinate {
        entryId = Objects.requireNonNull(entryId, "entryId");
        providerKey = Objects.requireNonNull(providerKey, "providerKey");
        modelId = Objects.requireNonNull(modelId, "modelId");
        catalogRevision = Objects.requireNonNull(catalogRevision, "catalogRevision");
    }

    @Override
    public String toString() {
        return providerKey + "/" + modelId + "#" + catalogRevision;
    }
}
