package io.crewscope.domain.collaboration;

import io.crewscope.domain.provider.ConnectionGrantId;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.team.TeamMemberId;
import java.util.Objects;

/** Exact provider recipient resolved from a current confirmed mapping. */
public record CollaborationRecipient(
        OrganizationId organizationId,
        TeamId teamId,
        TeamMemberId memberId,
        LarkMemberMappingId mappingId,
        long mappingVersion,
        ProviderBindingId providerBindingId,
        long providerBindingVersion,
        ConnectionId connectionId,
        long connectionVersion,
        ConnectionGrantId grantId,
        long grantVersion,
        LarkTenantKey tenantKey,
        LarkOpenId openId) {

    public CollaborationRecipient {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        memberId = Objects.requireNonNull(memberId, "memberId");
        mappingId = Objects.requireNonNull(mappingId, "mappingId");
        providerBindingId = Objects.requireNonNull(providerBindingId, "providerBindingId");
        connectionId = Objects.requireNonNull(connectionId, "connectionId");
        grantId = Objects.requireNonNull(grantId, "grantId");
        tenantKey = Objects.requireNonNull(tenantKey, "tenantKey");
        openId = Objects.requireNonNull(openId, "openId");
        if (mappingVersion < 0 || providerBindingVersion < 0
                || connectionVersion < 0 || grantVersion < 0) {
            throw new IllegalArgumentException("Collaboration recipient versions must not be negative");
        }
    }

    @Override
    public String toString() {
        return "CollaborationRecipient[organizationId=" + organizationId
                + ", teamId=" + teamId + ", memberId=" + memberId
                + ", externalIdentity=REDACTED]";
    }
}
