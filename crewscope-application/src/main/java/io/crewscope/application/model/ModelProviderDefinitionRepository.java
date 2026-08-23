package io.crewscope.application.model;

import io.crewscope.domain.model.ModelProviderDefinition;
import io.crewscope.domain.model.ModelProviderKey;
import java.util.Optional;

/** Persistence Port for trusted model provider definitions and lifecycle updates. */
public interface ModelProviderDefinitionRepository {

    /** Registers one immutable provider key and rejects duplicate keys. */
    ModelProviderDefinition register(ModelProviderDefinition definition);

    /** Updates lifecycle state with an optimistic lifecycle-version predicate. */
    ModelProviderDefinition updateLifecycle(ModelProviderDefinition definition);

    Optional<ModelProviderDefinition> findByKey(ModelProviderKey providerKey);
}
