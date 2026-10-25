package io.crewscope.application.model;

import io.crewscope.domain.model.ModelProviderDefinition;
import io.crewscope.domain.model.ModelRegistryHash;
import io.crewscope.domain.model.ModelProviderKey;
import java.util.List;
import java.util.Optional;

/** Persistence Port for trusted model provider definitions and lifecycle updates. */
public interface ModelProviderDefinitionRepository {

    /** Registers one immutable provider key and rejects duplicate keys. */
    ModelProviderDefinition register(ModelProviderDefinition definition);

    /** Updates lifecycle state with an optimistic lifecycle-version predicate. */
    ModelProviderDefinition updateLifecycle(ModelProviderDefinition definition);

    Optional<ModelProviderDefinition> findByKey(ModelProviderKey providerKey);

    /** Returns a stable provider-key ordered window for catalog management screens. */
    List<ModelProviderDefinition> findPage(int offset, int limit);

    /**
     * Adopts replacement content for an already-registered platform-owned provider. The provider
     * row is compare-and-swapped on {@code previousContentHash}, and every catalog entry,
     * connection and agent binding still pinning that hash is re-pointed to the replacement in
     * one transaction. Lifecycle state is carried by {@code replacement}; content facts outside
     * platform ownership are never touched.
     */
    default ModelProviderDefinition adoptPlatformContent(
            ModelRegistryHash previousContentHash,
            ModelProviderDefinition replacement) {
        throw new UnsupportedOperationException(
                "Platform provider content adoption is not implemented");
    }
}
