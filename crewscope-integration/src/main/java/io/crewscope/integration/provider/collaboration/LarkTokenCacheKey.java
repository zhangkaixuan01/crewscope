package io.crewscope.integration.provider.collaboration;

import io.crewscope.domain.collaboration.LarkTenantKey;
import io.crewscope.domain.provider.ConnectionGrantId;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.shared.id.CredentialId;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.Objects;

/** Complete authorization and secret coordinate for one cached tenant token. */
record LarkTokenCacheKey(
        OrganizationId organizationId,
        ConnectionId connectionId,
        long connectionVersion,
        ConnectionGrantId grantId,
        long grantVersion,
        CredentialId credentialId,
        long credentialVersion,
        long secretVersion,
        LarkTenantKey tenantKey) {

    LarkTokenCacheKey {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        connectionId = Objects.requireNonNull(connectionId, "connectionId");
        grantId = Objects.requireNonNull(grantId, "grantId");
        credentialId = Objects.requireNonNull(credentialId, "credentialId");
        tenantKey = Objects.requireNonNull(tenantKey, "tenantKey");
        if (connectionVersion < 0 || grantVersion < 0
                || credentialVersion < 0 || secretVersion < 0) {
            throw new IllegalArgumentException("Lark token cache versions must not be negative");
        }
    }

    @Override
    public String toString() {
        return "LarkTokenCacheKey[authorization=REDACTED]";
    }
}
