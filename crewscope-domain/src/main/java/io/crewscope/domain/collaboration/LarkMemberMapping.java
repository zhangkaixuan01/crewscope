package io.crewscope.domain.collaboration;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.provider.ConnectionGrantId;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import java.util.Objects;
import java.util.Optional;

/** Administrator-confirmed exact mapping from one TeamMember to one Lark open_id. */
public final class LarkMemberMapping {

    private final LarkMemberMappingId id;
    private final OrganizationId organizationId;
    private final TeamId teamId;
    private final TeamMemberId memberId;
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
    private final LarkVerificationSource verificationSource;
    private final UtcTimestamp verifiedAt;
    private final PrincipalId verifiedByPrincipalId;
    private final LarkMemberMappingStatus status;
    private final Optional<LarkMemberMappingTerminalReason> terminalReason;
    private final long version;
    private final AuditMetadata audit;

    private LarkMemberMapping(
            LarkMemberMappingId id,
            OrganizationId organizationId,
            TeamId teamId,
            TeamMemberId memberId,
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
            LarkVerificationSource verificationSource,
            UtcTimestamp verifiedAt,
            PrincipalId verifiedByPrincipalId,
            LarkMemberMappingStatus status,
            Optional<LarkMemberMappingTerminalReason> terminalReason,
            long version,
            AuditMetadata audit) {
        this.id = Objects.requireNonNull(id, "id");
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId");
        this.teamId = Objects.requireNonNull(teamId, "teamId");
        this.memberId = Objects.requireNonNull(memberId, "memberId");
        this.providerBindingId = Objects.requireNonNull(providerBindingId, "providerBindingId");
        this.connectionId = Objects.requireNonNull(connectionId, "connectionId");
        this.grantId = Objects.requireNonNull(grantId, "grantId");
        this.externalTenantId = Objects.requireNonNull(externalTenantId, "externalTenantId");
        this.tenantKey = Objects.requireNonNull(tenantKey, "tenantKey");
        this.openId = Objects.requireNonNull(openId, "openId");
        this.unionId = Objects.requireNonNull(unionId, "unionId");
        this.providerVersion = Objects.requireNonNull(providerVersion, "providerVersion");
        this.verificationSource = Objects.requireNonNull(verificationSource, "verificationSource");
        this.verifiedAt = Objects.requireNonNull(verifiedAt, "verifiedAt");
        this.verifiedByPrincipalId = Objects.requireNonNull(
                verifiedByPrincipalId, "verifiedByPrincipalId");
        this.status = Objects.requireNonNull(status, "status");
        this.terminalReason = Objects.requireNonNull(terminalReason, "terminalReason");
        this.audit = Objects.requireNonNull(audit, "audit");
        if (providerBindingVersion < 0 || connectionVersion < 0 || grantVersion < 0
                || externalTenantVersion < 0 || version < 0) {
            throw new DomainValidationException(
                    "larkMemberMapping.version", "all referenced and aggregate versions must not be negative");
        }
        this.providerBindingVersion = providerBindingVersion;
        this.connectionVersion = connectionVersion;
        this.grantVersion = grantVersion;
        this.externalTenantVersion = externalTenantVersion;
        this.version = version;
        if (status.terminal() != this.terminalReason.isPresent()) {
            throw new DomainValidationException(
                    "larkMemberMapping.terminalReason", "must exist exactly for a terminal status");
        }
        if (this.terminalReason.filter(reason -> !reason.supports(status)).isPresent()) {
            throw new DomainValidationException(
                    "larkMemberMapping.terminalReason", "is incompatible with the terminal status");
        }
    }

    /** Confirms one fresh exact proof after Team administration was authorized by the application. */
    public static LarkMemberMapping confirm(
            LarkMemberMappingId id,
            TeamMember member,
            LarkConnectionAuthorization authorization,
            LarkExternalTenant tenant,
            LarkMemberVerificationProof proof,
            Principal administrator,
            UtcTimestamp confirmedAt) {
        TeamMember requiredMember = Objects.requireNonNull(member, "member");
        LarkConnectionAuthorization current = Objects.requireNonNull(
                authorization, "authorization").requireCapabilities(
                        LarkCollaborationCapabilities.MEMBER_MAPPING);
        if (!requiredMember.canParticipate()
                || !requiredMember.scope().organizationId().equals(current.organizationId())
                || !requiredMember.scope().teamId().equals(current.teamId())) {
            throw new DomainValidationException(
                    "larkMemberMapping.memberId", "must be an ACTIVE member in the exact Team scope");
        }
        Principal actor = requireActor(administrator, current.organizationId(), current.teamId());
        LarkMemberVerificationProof requiredProof = Objects.requireNonNull(proof, "proof");
        LarkExternalTenant requiredTenant = Objects.requireNonNull(tenant, "tenant");
        requiredProof.requireConfirmable(current, requiredTenant, confirmedAt);
        return new LarkMemberMapping(
                id,
                current.organizationId(),
                current.teamId(),
                requiredMember.id(),
                current.providerBindingId(),
                current.providerBindingVersion(),
                current.connectionId(),
                current.connectionVersion(),
                current.grantId(),
                current.grantVersion(),
                requiredTenant.id(),
                requiredTenant.version(),
                requiredProof.tenantKey(),
                requiredProof.openId(),
                requiredProof.unionId(),
                requiredProof.providerVersion(),
                requiredProof.source(),
                requiredProof.verifiedAt(),
                actor.id(),
                LarkMemberMappingStatus.ACTIVE,
                Optional.empty(),
                0,
                AuditMetadata.createdBy(actor.id(), confirmedAt));
    }

    /** Reconstitutes current or historical mapping evidence without replaying confirmation. */
    public static LarkMemberMapping reconstitute(
            LarkMemberMappingId id,
            OrganizationId organizationId,
            TeamId teamId,
            TeamMemberId memberId,
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
            LarkVerificationSource verificationSource,
            UtcTimestamp verifiedAt,
            PrincipalId verifiedByPrincipalId,
            LarkMemberMappingStatus status,
            Optional<LarkMemberMappingTerminalReason> terminalReason,
            long version,
            AuditMetadata audit) {
        return new LarkMemberMapping(
                id, organizationId, teamId, memberId, providerBindingId,
                providerBindingVersion, connectionId, connectionVersion, grantId, grantVersion,
                externalTenantId, externalTenantVersion, tenantKey, openId, unionId,
                providerVersion, verificationSource, verifiedAt, verifiedByPrincipalId, status,
                terminalReason, version, audit);
    }

    public LarkMemberMapping terminate(
            long expectedVersion,
            LarkMemberMappingStatus target,
            LarkMemberMappingTerminalReason reason,
            Principal administrator,
            UtcTimestamp occurredAt) {
        requireVersion(expectedVersion);
        if (!target.terminal() || status != LarkMemberMappingStatus.ACTIVE) {
            throw new IllegalStateException("Lark member mapping terminal transition is not allowed");
        }
        Principal actor = requireActor(administrator, organizationId, teamId);
        return new LarkMemberMapping(
                id, organizationId, teamId, memberId, providerBindingId, providerBindingVersion,
                connectionId, connectionVersion, grantId, grantVersion, externalTenantId,
                externalTenantVersion, tenantKey, openId, unionId, providerVersion,
                verificationSource, verifiedAt, verifiedByPrincipalId, target,
                Optional.of(Objects.requireNonNull(reason, "reason")), version + 1,
                audit.modifiedBy(actor.id(), occurredAt));
    }

    /** Resolves an outbound recipient only while every mapping and authorization fact is current. */
    public CollaborationRecipient resolveRecipient(
            TeamMember member,
            LarkConnectionAuthorization authorization,
            LarkExternalTenant tenant) {
        TeamMember requiredMember = Objects.requireNonNull(member, "member");
        LarkConnectionAuthorization current = Objects.requireNonNull(
                authorization, "authorization").requireCapabilities(
                        LarkCollaborationCapabilities.NOTIFICATION_DELIVERY);
        LarkExternalTenant requiredTenant = Objects.requireNonNull(tenant, "tenant");
        if (status != LarkMemberMappingStatus.ACTIVE
                || !requiredMember.canParticipate()
                || !memberId.equals(requiredMember.id())
                || !organizationId.equals(requiredMember.scope().organizationId())
                || !teamId.equals(requiredMember.scope().teamId())
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
                    "collaborationRecipient", "requires a current ACTIVE exact member mapping");
        }
        return new CollaborationRecipient(
                organizationId, teamId, memberId, id, version, providerBindingId,
                providerBindingVersion, connectionId, connectionVersion, grantId, grantVersion,
                tenantKey, openId);
    }

    public LarkInternalMemberKey internalKey() {
        return new LarkInternalMemberKey(organizationId, teamId, memberId);
    }

    public LarkExternalMemberKey externalKey() {
        return new LarkExternalMemberKey(organizationId, tenantKey, openId);
    }

    public boolean usesIdentity(LarkMemberVerificationProof proof) {
        LarkMemberVerificationProof required = Objects.requireNonNull(proof, "proof");
        return organizationId.equals(required.organizationId())
                && teamId.equals(required.teamId())
                && tenantKey.equals(required.tenantKey())
                && openId.equals(required.openId());
    }

    public boolean usesVerification(LarkMemberVerificationProof proof) {
        LarkMemberVerificationProof required = Objects.requireNonNull(proof, "proof");
        return usesIdentity(required)
                && unionId.equals(required.unionId())
                && providerVersion.equals(required.providerVersion())
                && verificationSource == required.source();
    }

    public boolean isCurrentFor(
            LarkConnectionAuthorization authorization, LarkExternalTenant tenant) {
        LarkConnectionAuthorization current = Objects.requireNonNull(authorization, "authorization");
        LarkExternalTenant requiredTenant = Objects.requireNonNull(tenant, "tenant");
        return status == LarkMemberMappingStatus.ACTIVE
                && requiredTenant.isCurrent(current)
                && externalTenantId.equals(requiredTenant.id())
                && externalTenantVersion == requiredTenant.version()
                && organizationId.equals(current.organizationId())
                && teamId.equals(current.teamId())
                && providerBindingId.equals(current.providerBindingId())
                && providerBindingVersion == current.providerBindingVersion()
                && connectionId.equals(current.connectionId())
                && connectionVersion == current.connectionVersion()
                && grantId.equals(current.grantId())
                && grantVersion == current.grantVersion()
                && tenantKey.equals(current.expectedTenantKey());
    }

    private void requireVersion(long expectedVersion) {
        if (expectedVersion != version) {
            throw new OptimisticLockConflictException(
                    "LarkMemberMapping", id, expectedVersion, version);
        }
    }

    private static Principal requireActor(
            Principal actor, OrganizationId organizationId, TeamId teamId) {
        Principal required = Objects.requireNonNull(actor, "administrator");
        boolean wrongTeam = required.scope().teamId().isPresent()
                && required.scope().teamId().filter(teamId::equals).isEmpty();
        if (required.type() != PrincipalType.USER
                || !required.canAct()
                || !organizationId.equals(required.scope().organizationId())
                || wrongTeam) {
            throw new DomainValidationException(
                    "larkMemberMapping.administrator", "must be an active USER in scope");
        }
        return required;
    }

    public LarkMemberMappingId id() { return id; }
    public OrganizationId organizationId() { return organizationId; }
    public TeamId teamId() { return teamId; }
    public TeamMemberId memberId() { return memberId; }
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
    public LarkVerificationSource verificationSource() { return verificationSource; }
    public UtcTimestamp verifiedAt() { return verifiedAt; }
    public PrincipalId verifiedByPrincipalId() { return verifiedByPrincipalId; }
    public LarkMemberMappingStatus status() { return status; }
    public Optional<LarkMemberMappingTerminalReason> terminalReason() { return terminalReason; }
    public long version() { return version; }
    public AuditMetadata audit() { return audit; }
}
