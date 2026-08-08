package io.crewscope.application.provider;

import io.crewscope.domain.provider.ProviderDefinitionId;
import io.crewscope.domain.provider.ProviderImplementation;
import io.crewscope.domain.provider.ProviderImplementationId;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.List;
import java.util.Optional;

/** Persistence Port for concrete Provider implementations. */
public interface ProviderImplementationRepository {
    ProviderImplementation create(ProviderImplementation implementation);
    ProviderImplementation update(ProviderImplementation implementation);
    Optional<ProviderImplementation> findById(
            OrganizationId organizationId, ProviderImplementationId id);
    List<ProviderImplementation> findByDefinition(
            OrganizationId organizationId, ProviderDefinitionId definitionId);
}
