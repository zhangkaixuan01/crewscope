package io.crewscope.application.collaboration;

import io.crewscope.domain.collaboration.LarkExternalTenant;
import io.crewscope.domain.collaboration.LarkExternalTenantId;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.Optional;

/** Persistence Port for verified Lark tenant observations. */
public interface LarkExternalTenantRepository {

    Optional<LarkExternalTenant> findById(
            OrganizationId organizationId, LarkExternalTenantId id);

    Optional<LarkExternalTenant> findByConnection(
            OrganizationId organizationId, ConnectionId connectionId);

    LarkExternalTenant create(LarkExternalTenant tenant);

    LarkExternalTenant update(LarkExternalTenant tenant);
}
