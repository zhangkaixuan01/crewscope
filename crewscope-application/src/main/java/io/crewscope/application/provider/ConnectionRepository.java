package io.crewscope.application.provider;

import io.crewscope.domain.provider.Connection;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.provider.ProviderOwner;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.List;
import java.util.Optional;

/** Persistence Port for external identity Connections. */
public interface ConnectionRepository {
    Connection create(Connection connection);
    Connection update(Connection connection);
    Optional<Connection> findById(OrganizationId organizationId, ConnectionId id);
    List<Connection> findByOwner(ProviderOwner owner);
}
