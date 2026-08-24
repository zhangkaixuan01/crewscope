package io.crewscope.application.model;

import io.crewscope.domain.model.ModelCatalogCoordinate;
import io.crewscope.domain.model.ModelCatalogEntry;
import io.crewscope.domain.model.ModelCatalogEntryId;
import io.crewscope.domain.model.ModelCatalogRevision;
import io.crewscope.domain.model.ModelId;
import io.crewscope.domain.model.ModelProviderKey;
import java.util.List;
import java.util.Optional;

/** Persistence Port for append-only catalog revisions and mutable catalog lifecycle. */
public interface ModelCatalogEntryRepository {

    /** Rejects duplicate coordinates, revision gaps and a non-latest predecessor. */
    ModelCatalogEntry append(ModelCatalogEntry entry);

    /** Updates only lifecycle state with an optimistic lifecycle-version predicate. */
    ModelCatalogEntry updateLifecycle(ModelCatalogEntry entry);

    Optional<ModelCatalogEntry> findByCoordinate(ModelCatalogCoordinate coordinate);

    /** Resolves client-safe stable entry/revision IDs without accepting provider or model text. */
    default Optional<ModelCatalogEntry> findByEntryRevision(
            ModelCatalogEntryId entryId, ModelCatalogRevision revision) {
        throw new UnsupportedOperationException("Catalog entry/revision lookup is not implemented");
    }

    Optional<ModelCatalogEntry> findLatest(
            ModelProviderKey providerKey, ModelId modelId);

    /** Returns one provider's catalog in model/revision order without hydrating relationships. */
    List<ModelCatalogEntry> findPage(ModelProviderKey providerKey, int offset, int limit);
}
