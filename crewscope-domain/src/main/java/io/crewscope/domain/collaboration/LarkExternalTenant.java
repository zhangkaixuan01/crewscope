package io.crewscope.domain.collaboration;

import io.crewscope.domain.provider.ConnectionGrantId;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;

/** Versioned proof that an authorized Connection resolves to its configured Lark tenant. */
public final class LarkExternalTenant {

    private final LarkExternalTenantId id;
    private final io.crewscope.domain.shared.id.OrganizationId organizationId;
    private final ConnectionId connectionId;
    private final long connectionVersion;
    private final ConnectionGrantId grantId;
    private final long grantVersion;
    private final LarkTenantKey tenantKey;
    private final LarkProviderVersion providerVersion;
    private final LarkExternalTenantStatus status;
    private final UtcTimestamp verifiedAt;
    private final long version;

    private LarkExternalTenant(
            LarkExternalTenantId id,
            io.crewscope.domain.shared.id.OrganizationId organizationId,
            ConnectionId connectionId,
            long connectionVersion,
            ConnectionGrantId grantId,
            long grantVersion,
            LarkTenantKey tenantKey,
            LarkProviderVersion providerVersion,
            LarkExternalTenantStatus status,
            UtcTimestamp verifiedAt,
            long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId");
        this.connectionId = Objects.requireNonNull(connectionId, "connectionId");
        this.grantId = Objects.requireNonNull(grantId, "grantId");
        this.tenantKey = Objects.requireNonNull(tenantKey, "tenantKey");
        this.providerVersion = Objects.requireNonNull(providerVersion, "providerVersion");
        this.status = Objects.requireNonNull(status, "status");
        this.verifiedAt = Objects.requireNonNull(verifiedAt, "verifiedAt");
        if (connectionVersion < 0 || grantVersion < 0 || version < 0) {
            throw new DomainValidationException(
                    "larkExternalTenant.version", "authorization and aggregate versions must not be negative");
        }
        this.connectionVersion = connectionVersion;
        this.grantVersion = grantVersion;
        this.version = version;
        if (!id.equals(LarkExternalTenantId.derive(organizationId, connectionId))) {
            throw new DomainValidationException(
                    "larkExternalTenant.id", "must be derived from Organization and Connection");
        }
    }

    /** Creates a tenant proof only when the OpenAPI result equals the configured tenant_key. */
    public static LarkExternalTenant verify(
            LarkConnectionAuthorization authorization,
            LarkTenantKey observedTenantKey,
            LarkProviderVersion providerVersion,
            UtcTimestamp verifiedAt) {
        LarkConnectionAuthorization current = Objects.requireNonNull(
                authorization, "authorization").requireCapabilities(
                        LarkCollaborationCapabilities.MEMBER_MAPPING);
        LarkTenantKey observed = Objects.requireNonNull(observedTenantKey, "observedTenantKey");
        if (!current.expectedTenantKey().equals(observed)) {
            throw new DomainValidationException(
                    "larkExternalTenant.tenantKey", "must equal the configured tenant_key");
        }
        return new LarkExternalTenant(
                LarkExternalTenantId.derive(current.organizationId(), current.connectionId()),
                current.organizationId(),
                current.connectionId(),
                current.connectionVersion(),
                current.grantId(),
                current.grantVersion(),
                observed,
                providerVersion,
                LarkExternalTenantStatus.VERIFIED,
                verifiedAt,
                0);
    }

    public static LarkExternalTenant reconstitute(
            LarkExternalTenantId id,
            io.crewscope.domain.shared.id.OrganizationId organizationId,
            ConnectionId connectionId,
            long connectionVersion,
            ConnectionGrantId grantId,
            long grantVersion,
            LarkTenantKey tenantKey,
            LarkProviderVersion providerVersion,
            LarkExternalTenantStatus status,
            UtcTimestamp verifiedAt,
            long version) {
        return new LarkExternalTenant(
                id, organizationId, connectionId, connectionVersion, grantId, grantVersion,
                tenantKey, providerVersion, status, verifiedAt, version);
    }

    /** Records a new exact tenant verification and advances the proof version. */
    public LarkExternalTenant refresh(
            long expectedVersion,
            LarkConnectionAuthorization authorization,
            LarkTenantKey observedTenantKey,
            LarkProviderVersion observedProviderVersion,
            UtcTimestamp occurredAt) {
        requireVersion(expectedVersion);
        if (status == LarkExternalTenantStatus.INVALIDATED) {
            throw new IllegalStateException(
                    "Invalidated Lark external tenant evidence cannot be refreshed");
        }
        LarkConnectionAuthorization current = Objects.requireNonNull(
                authorization, "authorization").requireCapabilities(
                        LarkCollaborationCapabilities.MEMBER_MAPPING);
        LarkTenantKey observed = Objects.requireNonNull(observedTenantKey, "observedTenantKey");
        if (!organizationId.equals(current.organizationId())
                || !connectionId.equals(current.connectionId())
                || !current.expectedTenantKey().equals(observed)) {
            throw new DomainValidationException(
                    "larkExternalTenant", "cannot refresh across Organization, Connection or tenant_key");
        }
        if (isCurrent(current)
                && providerVersion.equals(observedProviderVersion)) {
            return this;
        }
        return new LarkExternalTenant(
                id,
                organizationId,
                connectionId,
                current.connectionVersion(),
                current.grantId(),
                current.grantVersion(),
                observed,
                observedProviderVersion,
                LarkExternalTenantStatus.VERIFIED,
                occurredAt,
                version + 1);
    }

    /** Invalidates the observation after revocation or identity drift without deleting evidence. */
    public LarkExternalTenant invalidate(long expectedVersion) {
        requireVersion(expectedVersion);
        if (status == LarkExternalTenantStatus.INVALIDATED) {
            return this;
        }
        return new LarkExternalTenant(
                id, organizationId, connectionId, connectionVersion, grantId, grantVersion,
                tenantKey, providerVersion, LarkExternalTenantStatus.INVALIDATED, verifiedAt,
                version + 1);
    }

    public boolean isCurrent(LarkConnectionAuthorization authorization) {
        LarkConnectionAuthorization current = Objects.requireNonNull(authorization, "authorization");
        return status == LarkExternalTenantStatus.VERIFIED
                && organizationId.equals(current.organizationId())
                && connectionId.equals(current.connectionId())
                && connectionVersion == current.connectionVersion()
                && grantId.equals(current.grantId())
                && grantVersion == current.grantVersion()
                && tenantKey.equals(current.expectedTenantKey());
    }

    private void requireVersion(long expectedVersion) {
        if (expectedVersion != version) {
            throw new IllegalStateException("Lark external tenant version conflict");
        }
    }

    public LarkExternalTenantId id() { return id; }
    public io.crewscope.domain.shared.id.OrganizationId organizationId() { return organizationId; }
    public ConnectionId connectionId() { return connectionId; }
    public long connectionVersion() { return connectionVersion; }
    public ConnectionGrantId grantId() { return grantId; }
    public long grantVersion() { return grantVersion; }
    public LarkTenantKey tenantKey() { return tenantKey; }
    public LarkProviderVersion providerVersion() { return providerVersion; }
    public LarkExternalTenantStatus status() { return status; }
    public UtcTimestamp verifiedAt() { return verifiedAt; }
    public long version() { return version; }
}
