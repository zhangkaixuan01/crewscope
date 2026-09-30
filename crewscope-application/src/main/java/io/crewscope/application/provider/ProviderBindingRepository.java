package io.crewscope.application.provider;

import io.crewscope.domain.provider.ProviderBinding;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.provider.ProviderOwner;
import io.crewscope.domain.provider.ProviderType;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import java.util.List;
import java.util.Optional;

/** Persistence Port for version-pinned Provider bindings and resolver candidate reads. */
public interface ProviderBindingRepository {
    ProviderBinding create(ProviderBinding binding);
    ProviderBinding update(ProviderBinding binding);
    Optional<ProviderBinding> findById(OrganizationId organizationId, ProviderBindingId id);
    List<ProviderBinding> findCandidates(ProviderBindingQuery query);

    /**
     * Active default bindings at the WORKSPACE level of one exact owner and provider type —
     * the row set the {@code ux_provider_binding_active_default} partial unique index caps at
     * one. Read before creating another default so the conflict can name the existing binding
     * (M9b-Q02 defect 23).
     */
    List<ProviderBinding> findActiveWorkspaceDefaults(
            OrganizationId organizationId,
            TeamId teamId,
            WorkspaceId workspaceId,
            ProviderOwner owner,
            ProviderType providerType);
}
