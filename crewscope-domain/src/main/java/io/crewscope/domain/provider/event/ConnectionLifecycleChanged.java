package io.crewscope.domain.provider.event;

import io.crewscope.domain.provider.Connection;
import io.crewscope.domain.provider.ConnectionStatus;
import io.crewscope.domain.provider.ProviderOwnerType;
import io.crewscope.domain.shared.DomainEvent;
import java.util.Objects;

/** Version 1 non-secret payload for an external Connection lifecycle change. */
public record ConnectionLifecycleChanged(
        String connectorKey,
        ProviderOwnerType ownerType,
        ConnectionStatus status)
        implements DomainEvent {

    public ConnectionLifecycleChanged {
        if (connectorKey == null || connectorKey.isBlank()) {
            throw new IllegalArgumentException("connectorKey must not be blank");
        }
        connectorKey = connectorKey.strip();
        Objects.requireNonNull(ownerType, "ownerType");
        Objects.requireNonNull(status, "status");
    }

    public static ConnectionLifecycleChanged from(Connection connection) {
        Connection value = Objects.requireNonNull(connection, "connection");
        return new ConnectionLifecycleChanged(
                value.connectorKey(), value.owner().type(), value.status());
    }
}
