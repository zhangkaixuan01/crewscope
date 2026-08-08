package io.crewscope.application.provider;

import io.crewscope.domain.provider.ProviderDefinition;
import io.crewscope.domain.provider.ProviderDefinitionId;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.Optional;

/** Persistence Port for Organization-scoped Provider definitions. */
public interface ProviderDefinitionRepository {
    ProviderDefinition create(ProviderDefinition definition);
    ProviderDefinition update(ProviderDefinition definition);
    Optional<ProviderDefinition> findById(OrganizationId organizationId, ProviderDefinitionId id);
    Optional<ProviderDefinition> findByKey(OrganizationId organizationId, String key);
}
