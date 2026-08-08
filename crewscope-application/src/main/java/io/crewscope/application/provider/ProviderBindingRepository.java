package io.crewscope.application.provider;

import io.crewscope.domain.provider.ProviderBinding;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.List;
import java.util.Optional;

/** Persistence Port for version-pinned Provider bindings and resolver candidate reads. */
public interface ProviderBindingRepository {
    ProviderBinding create(ProviderBinding binding);
    ProviderBinding update(ProviderBinding binding);
    Optional<ProviderBinding> findById(OrganizationId organizationId, ProviderBindingId id);
    List<ProviderBinding> findCandidates(ProviderBindingQuery query);
}
