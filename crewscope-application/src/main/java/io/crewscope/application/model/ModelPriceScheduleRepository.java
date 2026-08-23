package io.crewscope.application.model;

import io.crewscope.domain.model.ModelCatalogCoordinate;
import io.crewscope.domain.model.ModelPriceRevision;
import io.crewscope.domain.model.ModelPriceSchedule;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Optional;

/** Persistence Port for append-only price revisions of exact model catalog revisions. */
public interface ModelPriceScheduleRepository {

    /** Rejects duplicate revisions, revision gaps and non-increasing effective times. */
    ModelPriceRevision append(ModelPriceRevision priceRevision);

    Optional<ModelPriceSchedule> findSchedule(ModelCatalogCoordinate coordinate);

    Optional<ModelPriceRevision> findEffectivePrice(
            ModelCatalogCoordinate coordinate, UtcTimestamp effectiveAt);
}
