package io.crewscope.domain.collaboration;

import io.crewscope.domain.provider.ConnectionGrantId;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.time.Duration;
import java.util.Objects;

/** Short-lived exact member proof bound to the complete current authorization coordinates. */
public final class LarkMemberVerificationProof {

    public static final Duration MAX_CONFIRMATION_WINDOW = Duration.ofMinutes(15);

    private final LarkMemberVerificationProofId id;
    private final OrganizationId organizationId;
    private final TeamId teamId;
    private final ProviderBindingId providerBindingId;
    private final long providerBindingVersion;
    private final ConnectionId connectionId;
    private final long connectionVersion;
    private final ConnectionGrantId grantId;
    private final long grantVersion;
    private final LarkExternalTenantId externalTenantId;
    private final long externalTenantVersion;
    private final LarkTenantKey tenantKey;
    private final LarkOpenId openId;
    private final LarkUnionId unionId;
    private final LarkProviderVersion providerVersion;
    private final LarkVerificationSource source;
    private final LarkVerificationStatus status;
    private final UtcTimestamp verifiedAt;
    private final UtcTimestamp validUntil;

    private LarkMemberVerificationProof(
            LarkMemberVerificationProofId id,
            OrganizationId organizationId,
            TeamId teamId,
            ProviderBindingId providerBindingId,
            long providerBindingVersion,
            ConnectionId connectionId,
            long connectionVersion,
            ConnectionGrantId grantId,
            long grantVersion,
            LarkExternalTenantId externalTenantId,
            long externalTenantVersion,
            LarkTenantKey tenantKey,
            LarkOpenId openId,
            LarkUnionId unionId,
            LarkProviderVersion providerVersion,
            LarkVerificationSource source,
            LarkVerificationStatus status,
            UtcTimestamp verifiedAt,
            UtcTimestamp validUntil) {
        this.id = Objects.requireNonNull(id, "id");
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId");
        this.teamId = Objects.requireNonNull(teamId, "teamId");
        this.providerBindingId = Objects.requireNonNull(providerBindingId, "providerBindingId");
        this.connectionId = Objects.requireNonNull(connectionId, "connectionId");
        this.grantId = Objects.requireNonNull(grantId, "grantId");
        this.externalTenantId = Objects.requireNonNull(externalTenantId, "externalTenantId");
        this.tenantKey = Objects.requireNonNull(tenantKey, "tenantKey");
        this.openId = Objects.requireNonNull(openId, "openId");
        this.unionId = Objects.requireNonNull(unionId, "unionId");
        this.providerVersion = Objects.requireNonNull(providerVersion, "providerVersion");
        this.source = Objects.requireNonNull(source, "source");
        this.status = Objects.requireNonNull(status, "status");
        this.verifiedAt = Objects.requireNonNull(verifiedAt, "verifiedAt");
        this.validUntil = Objects.requireNonNull(validUntil, "validUntil");
        if (providerBindingVersion < 0 || connectionVersion < 0 || grantVersion < 0
                || externalTenantVersion < 0) {
            throw new DomainValidationException(
                    "larkMemberVerificationProof.version", "all referenced versions must not be negative");
        }
        this.providerBindingVersion = providerBindingVersion;
        this.connectionVersion = connectionVersion;
        this.grantVersion = grantVersion;
        this.externalTenantVersion = externalTenantVersion;
        Duration window = Duration.between(verifiedAt.value(), validUntil.value());
        if (window.isNegative() || window.isZero() || window.compareTo(MAX_CONFIRMATION_WINDOW) > 0) {
            throw new DomainValidationException(
                    "larkMemberVerificationProof.validUntil",
                    "must be after verifiedAt and within 15 minutes");
        }
    }

    public static LarkMemberVerificationProof verified(
            LarkMemberVerificationProofId id,
            LarkConnectionAuthorization authorization,
            LarkExternalTenant tenant,
            LarkOpenId requestedOpenId,
            LarkOpenId observedOpenId,
            LarkUnionId unionId,
            LarkProviderVersion providerVersion,
            UtcTimestamp verifiedAt,
            UtcTimestamp validUntil) {
        LarkConnectionAuthorization current = Objects.requireNonNull(
                authorization, "authorization").requireCapabilities(
                        LarkCollaborationCapabilities.MEMBER_MAPPING);
        LarkExternalTenant requiredTenant = Objects.requireNonNull(tenant, "tenant");
        if (!requiredTenant.isCurrent(current)) {
            throw new DomainValidationException(
                    "larkMemberVerificationProof.externalTenantId", "must be current");
        }
        LarkOpenId requested = Objects.requireNonNull(requestedOpenId, "requestedOpenId");
        if (!requested.equals(Objects.requireNonNull(observedOpenId, "observedOpenId"))) {
            throw new DomainValidationException(
                    "larkMemberVerificationProof.openId", "must equal the exact requested open_id");
        }
        return new LarkMemberVerificationProof(
                id,
                current.organizationId(),
                current.teamId(),
                current.providerBindingId(),
                current.providerBindingVersion(),
                current.connectionId(),
                current.connectionVersion(),
                current.grantId(),
                current.grantVersion(),
                requiredTenant.id(),
                requiredTenant.version(),
                requiredTenant.tenantKey(),
                requested,
                unionId,
                providerVersion,
                LarkVerificationSource.LARK_OPEN_API_EXACT_OPEN_ID,
                LarkVerificationStatus.VERIFIED,
                verifiedAt,
                validUntil);
    }

    /** Reconstitutes persisted immutable verification evidence without repeating OpenAPI lookup. */
    public static LarkMemberVerificationProof reconstitute(
            LarkMemberVerificationProofId id,
            OrganizationId organizationId,
            TeamId teamId,
            ProviderBindingId providerBindingId,
            long providerBindingVersion,
            ConnectionId connectionId,
            long connectionVersion,
            ConnectionGrantId grantId,
            long grantVersion,
            LarkExternalTenantId externalTenantId,
            long externalTenantVersion,
            LarkTenantKey tenantKey,
            LarkOpenId openId,
            LarkUnionId unionId,
            LarkProviderVersion providerVersion,
            LarkVerificationSource source,
            LarkVerificationStatus status,
            UtcTimestamp verifiedAt,
            UtcTimestamp validUntil) {
        return new LarkMemberVerificationProof(
                id, organizationId, teamId, providerBindingId, providerBindingVersion,
                connectionId, connectionVersion, grantId, grantVersion, externalTenantId,
                externalTenantVersion, tenantKey, openId, unionId, providerVersion, source,
                status, verifiedAt, validUntil);
    }

    /** Rejects stale time, Tenant, Binding, Connection, Grant and scope facts. */
    public void requireConfirmable(
            LarkConnectionAuthorization authorization,
            LarkExternalTenant tenant,
            UtcTimestamp now) {
        LarkConnectionAuthorization current = Objects.requireNonNull(
                authorization, "authorization").requireCapabilities(
                        LarkCollaborationCapabilities.MEMBER_MAPPING);
        LarkExternalTenant requiredTenant = Objects.requireNonNull(tenant, "tenant");
        UtcTimestamp requiredNow = Objects.requireNonNull(now, "now");
        if (status != LarkVerificationStatus.VERIFIED
                || requiredNow.compareTo(verifiedAt) < 0
                || requiredNow.compareTo(validUntil) >= 0
                || !requiredTenant.isCurrent(current)
                || !externalTenantId.equals(requiredTenant.id())
                || externalTenantVersion != requiredTenant.version()
                || !organizationId.equals(current.organizationId())
                || !teamId.equals(current.teamId())
                || !providerBindingId.equals(current.providerBindingId())
                || providerBindingVersion != current.providerBindingVersion()
                || !connectionId.equals(current.connectionId())
                || connectionVersion != current.connectionVersion()
                || !grantId.equals(current.grantId())
                || grantVersion != current.grantVersion()
                || !tenantKey.equals(current.expectedTenantKey())) {
            throw new DomainValidationException(
                    "larkMemberVerificationProof", "is expired, invalidated or authorization-drifted");
        }
    }

    public LarkMemberVerificationProofId id() { return id; }
    public OrganizationId organizationId() { return organizationId; }
    public TeamId teamId() { return teamId; }
    public ProviderBindingId providerBindingId() { return providerBindingId; }
    public long providerBindingVersion() { return providerBindingVersion; }
    public ConnectionId connectionId() { return connectionId; }
    public long connectionVersion() { return connectionVersion; }
    public ConnectionGrantId grantId() { return grantId; }
    public long grantVersion() { return grantVersion; }
    public LarkExternalTenantId externalTenantId() { return externalTenantId; }
    public long externalTenantVersion() { return externalTenantVersion; }
    public LarkTenantKey tenantKey() { return tenantKey; }
    public LarkOpenId openId() { return openId; }
    public LarkUnionId unionId() { return unionId; }
    public LarkProviderVersion providerVersion() { return providerVersion; }
    public LarkVerificationSource source() { return source; }
    public LarkVerificationStatus status() { return status; }
    public UtcTimestamp verifiedAt() { return verifiedAt; }
    public UtcTimestamp validUntil() { return validUntil; }
}
