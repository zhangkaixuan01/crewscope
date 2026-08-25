package io.crewscope.domain.collaboration;

import io.crewscope.domain.shared.id.AggregateId;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** Stable tenant observation identity scoped to one CrewScope Connection. */
public record LarkExternalTenantId(UUID value) implements AggregateId {

    public LarkExternalTenantId {
        value = AggregateId.requireValue(value, "LarkExternalTenantId");
    }

    public static LarkExternalTenantId derive(
            io.crewscope.domain.shared.id.OrganizationId organizationId,
            io.crewscope.domain.provider.ConnectionId connectionId) {
        String canonical = "lark-external-tenant-v1:"
                + Objects.requireNonNull(organizationId, "organizationId") + ':'
                + Objects.requireNonNull(connectionId, "connectionId");
        return new LarkExternalTenantId(
                UUID.nameUUIDFromBytes(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
