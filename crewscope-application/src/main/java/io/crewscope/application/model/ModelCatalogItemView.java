package io.crewscope.application.model;

import io.crewscope.domain.model.ModelCatalogEntry;
import io.crewscope.domain.model.ModelPriceRevision;
import java.util.Objects;
import java.util.Optional;

/** One catalog revision and its price effective at query time. */
public record ModelCatalogItemView(
        ModelCatalogEntry catalog, Optional<ModelPriceRevision> effectivePrice) {

    public ModelCatalogItemView {
        Objects.requireNonNull(catalog, "catalog");
        effectivePrice = Objects.requireNonNull(effectivePrice, "effectivePrice");
        effectivePrice.ifPresent(price -> {
            if (!price.catalogCoordinate().equals(catalog.coordinate())) {
                throw new IllegalArgumentException("effectivePrice must match the catalog coordinate");
            }
        });
    }
}
