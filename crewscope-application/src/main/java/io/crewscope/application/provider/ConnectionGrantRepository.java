package io.crewscope.application.provider;

import io.crewscope.domain.provider.ConnectionGrant;
import io.crewscope.domain.provider.ConnectionGrantId;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.provider.ProviderOwner;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.List;
import java.util.Optional;

/** Persistence Port for Connection authorization grants. */
public interface ConnectionGrantRepository {
    ConnectionGrant create(ConnectionGrant grant);
    ConnectionGrant update(ConnectionGrant grant);
    Optional<ConnectionGrant> findById(OrganizationId organizationId, ConnectionGrantId id);
    List<ConnectionGrant> findByConnectionAndGrantee(ConnectionId connectionId, ProviderOwner grantee);
}
