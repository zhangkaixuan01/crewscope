package io.crewscope.application.model;

import io.crewscope.domain.model.ModelConnection;
import io.crewscope.domain.model.ModelConnectionId;
import io.crewscope.domain.model.ModelConnectionOwner;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.List;
import java.util.Optional;

/** Persistence Port for tenant-scoped model connection registration and optimistic mutation. */
public interface ModelConnectionRepository {

    /** Registers a stable connection and rejects duplicate tenant-qualified identifiers. */
    ModelConnection register(ModelConnection connection);

    /** Updates one connection using the immediately preceding optimistic version. */
    ModelConnection update(ModelConnection connection);

    Optional<ModelConnection> findById(
            OrganizationId organizationId, ModelConnectionId connectionId);

    List<ModelConnection> findByOwner(ModelConnectionOwner owner);
}
