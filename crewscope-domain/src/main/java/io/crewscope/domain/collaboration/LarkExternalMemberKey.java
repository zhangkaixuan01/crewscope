package io.crewscope.domain.collaboration;

import io.crewscope.domain.shared.id.OrganizationId;
import java.util.Objects;

/** Organization-isolated unique external side of one active Lark mapping. */
public record LarkExternalMemberKey(
        OrganizationId organizationId, LarkTenantKey tenantKey, LarkOpenId openId) {
    public LarkExternalMemberKey {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        tenantKey = Objects.requireNonNull(tenantKey, "tenantKey");
        openId = Objects.requireNonNull(openId, "openId");
    }

    @Override
    public String toString() {
        return "LarkExternalMemberKey[organizationId=" + organizationId + ", identity=REDACTED]";
    }
}
