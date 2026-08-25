package io.crewscope.domain.collaboration;

import io.crewscope.domain.provider.ConnectionGrantId;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.provider.ProviderCapabilities;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.util.Objects;

/** Current ADR-006 result pinned to one Lark Binding, Connection and Grant. */
public record LarkConnectionAuthorization(
        OrganizationId organizationId,
        TeamId teamId,
        ProviderBindingId providerBindingId,
        long providerBindingVersion,
        ConnectionId connectionId,
        long connectionVersion,
        ConnectionGrantId grantId,
        long grantVersion,
        LarkTenantKey expectedTenantKey,
        ProviderCapabilities effectiveCapabilities) {

    public LarkConnectionAuthorization {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        providerBindingId = Objects.requireNonNull(providerBindingId, "providerBindingId");
        connectionId = Objects.requireNonNull(connectionId, "connectionId");
        grantId = Objects.requireNonNull(grantId, "grantId");
        expectedTenantKey = Objects.requireNonNull(expectedTenantKey, "expectedTenantKey");
        effectiveCapabilities = Objects.requireNonNull(
                effectiveCapabilities, "effectiveCapabilities");
        requireVersion(providerBindingVersion, "providerBindingVersion");
        requireVersion(connectionVersion, "connectionVersion");
        requireVersion(grantVersion, "grantVersion");
    }

    /** Fails closed unless the exact requested Lark capabilities survived all intersections. */
    public LarkConnectionAuthorization requireCapabilities(ProviderCapabilities required) {
        if (!effectiveCapabilities.includes(Objects.requireNonNull(required, "required"))) {
            throw new DomainValidationException(
                    "larkConnectionAuthorization.capabilities",
                    "does not contain the exact requested Collaboration capability");
        }
        return this;
    }

    public boolean sameAuthorization(LarkConnectionAuthorization other) {
        LarkConnectionAuthorization value = Objects.requireNonNull(other, "other");
        return organizationId.equals(value.organizationId)
                && teamId.equals(value.teamId)
                && providerBindingId.equals(value.providerBindingId)
                && providerBindingVersion == value.providerBindingVersion
                && connectionId.equals(value.connectionId)
                && connectionVersion == value.connectionVersion
                && grantId.equals(value.grantId)
                && grantVersion == value.grantVersion
                && expectedTenantKey.equals(value.expectedTenantKey);
    }

    private static void requireVersion(long value, String name) {
        if (value < 0) {
            throw new DomainValidationException(
                    "larkConnectionAuthorization." + name, "must not be negative");
        }
    }
}
