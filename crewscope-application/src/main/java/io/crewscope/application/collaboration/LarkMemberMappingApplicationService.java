package io.crewscope.application.collaboration;

import io.crewscope.application.team.TeamMemberRepository;
import io.crewscope.domain.collaboration.CollaborationRecipient;
import io.crewscope.domain.collaboration.LarkCollaborationCapabilities;
import io.crewscope.domain.collaboration.LarkConnectionAuthorization;
import io.crewscope.domain.collaboration.LarkExternalTenant;
import io.crewscope.domain.collaboration.LarkMemberMapping;
import io.crewscope.domain.collaboration.LarkMemberMappingId;
import io.crewscope.domain.collaboration.LarkMemberMappingStatus;
import io.crewscope.domain.collaboration.LarkMemberMappingTerminalReason;
import io.crewscope.domain.collaboration.LarkMemberVerificationProof;
import io.crewscope.domain.collaboration.LarkMemberVerificationProofId;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** Verifies, confirms, revokes and resolves exact Lark member mappings. */
public final class LarkMemberMappingApplicationService {

    private final LarkConnectionAuthorizationResolver authorizations;
    private final LarkMappingAdministration administration;
    private final LarkIdentityVerificationPort verification;
    private final LarkExternalTenantRepository tenants;
    private final LarkMemberVerificationProofRepository proofs;
    private final LarkMemberMappingRepository mappings;
    private final TeamMemberRepository members;
    private final TimeProvider timeProvider;
    private final Duration confirmationWindow;

    public LarkMemberMappingApplicationService(
            LarkConnectionAuthorizationResolver authorizations,
            LarkMappingAdministration administration,
            LarkIdentityVerificationPort verification,
            LarkExternalTenantRepository tenants,
            LarkMemberVerificationProofRepository proofs,
            LarkMemberMappingRepository mappings,
            TeamMemberRepository members,
            TimeProvider timeProvider,
            Duration confirmationWindow) {
        this.authorizations = Objects.requireNonNull(authorizations, "authorizations");
        this.administration = Objects.requireNonNull(administration, "administration");
        this.verification = Objects.requireNonNull(verification, "verification");
        this.tenants = Objects.requireNonNull(tenants, "tenants");
        this.proofs = Objects.requireNonNull(proofs, "proofs");
        this.mappings = Objects.requireNonNull(mappings, "mappings");
        this.members = Objects.requireNonNull(members, "members");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
        this.confirmationWindow = requireWindow(confirmationWindow);
    }

    /** Performs only fixed tenant and exact open_id queries, then persists a short-lived proof. */
    public LarkMemberVerificationProof verifyMember(VerifyLarkMemberCommand command) {
        VerifyLarkMemberCommand required = Objects.requireNonNull(command, "command");
        UtcTimestamp now = timeProvider.now();
        administration.requireProviderAdministrator(
                required.organizationId(), required.teamId(), required.actor(), now);
        LarkConnectionAuthorization authorization = authorization(
                required.organizationId(),
                required.teamId(),
                required.providerBindingId(),
                LarkCollaborationCapabilities.MEMBER_MAPPING);
        LarkTenantObservation tenantObservation = verification.verifyTenant(
                authorization, required.actor().id());
        LarkExternalTenant tenant = upsertTenant(authorization, tenantObservation);
        LarkMemberObservation member = verification.verifyMember(
                authorization, tenant, required.openId(), required.actor().id());
        LarkMemberVerificationProof proof = LarkMemberVerificationProof.verified(
                LarkMemberVerificationProofId.generate(),
                authorization,
                tenant,
                required.openId(),
                member.openId(),
                member.unionId(),
                member.providerVersion(),
                member.observedAt(),
                plus(member.observedAt(), confirmationWindow));
        return proofs.create(proof);
    }

    /** Confirms a current proof and atomically enforces internal/external active uniqueness. */
    public LarkMemberMapping confirmMapping(ConfirmLarkMemberMappingCommand command) {
        ConfirmLarkMemberMappingCommand required = Objects.requireNonNull(command, "command");
        UtcTimestamp now = timeProvider.now();
        administration.requireProviderAdministrator(
                required.organizationId(), required.teamId(), required.actor(), now);
        TeamMember member = requireMember(
                required.organizationId(), required.teamId(), required.memberId());
        LarkMemberVerificationProof proof = requireProof(
                required.organizationId(), required.proofId());
        if (!required.teamId().equals(proof.teamId())
                || !required.providerBindingId().equals(proof.providerBindingId())) {
            throw new DomainValidationException(
                    "confirmLarkMemberMapping.proofId", "must belong to the exact Team and Binding");
        }
        LarkConnectionAuthorization authorization = authorization(
                required.organizationId(),
                required.teamId(),
                required.providerBindingId(),
                LarkCollaborationCapabilities.MEMBER_MAPPING);
        LarkExternalTenant tenant = requireTenant(
                required.organizationId(), proof.externalTenantId());
        proof.requireConfirmable(authorization, tenant, now);
        LarkMemberMapping candidate = LarkMemberMapping.confirm(
                LarkMemberMappingId.generate(), member, authorization, tenant, proof,
                required.actor(), now);
        Optional<LarkMemberMapping> internal = mappings.findActiveByInternalKey(
                candidate.internalKey());
        Optional<LarkMemberMapping> external = mappings.findActiveByExternalKey(
                candidate.externalKey());
        if (internal.isEmpty() && external.isPresent()) {
            throw new DomainValidationException(
                    "larkMemberMapping.openId", "is already mapped inside this Organization");
        }
        if (internal.isEmpty()) {
            return mappings.createActive(candidate);
        }
        LarkMemberMapping existing = internal.orElseThrow();
        if (!existing.usesIdentity(proof)) {
            throw new DomainValidationException(
                    "larkMemberMapping.memberId", "already has another active Lark identity");
        }
        if (external.filter(value -> value.id().equals(existing.id())).isEmpty()) {
            throw new IllegalStateException("Lark mapping unique indexes are inconsistent");
        }
        if (existing.isCurrentFor(authorization, tenant)
                && existing.usesVerification(proof)) {
            return existing;
        }
        LarkMemberMapping invalidated = existing.terminate(
                existing.version(),
                LarkMemberMappingStatus.INVALIDATED,
                LarkMemberMappingTerminalReason.AUTHORIZATION_DRIFT,
                required.actor(),
                now);
        return mappings.replaceActive(invalidated, candidate);
    }

    public LarkMemberMapping revokeMapping(RevokeLarkMemberMappingCommand command) {
        RevokeLarkMemberMappingCommand required = Objects.requireNonNull(command, "command");
        LarkMemberMapping mapping = mappings.findById(
                        required.organizationId(), required.mappingId())
                .orElseThrow(() -> new IllegalArgumentException("Lark member mapping was not found"));
        UtcTimestamp now = timeProvider.now();
        administration.requireProviderAdministrator(
                required.organizationId(), mapping.teamId(), required.actor(), now);
        return mappings.update(mapping.terminate(
                required.expectedVersion(), LarkMemberMappingStatus.REVOKED,
                required.reason(), required.actor(), now));
    }

    /** Returns only one administrator-authorized, exact-Team, stable keyset page. */
    public LarkMemberMappingPage listMappings(ListLarkMemberMappingsQuery query) {
        ListLarkMemberMappingsQuery required = Objects.requireNonNull(query, "query");
        UtcTimestamp now = timeProvider.now();
        administration.requireProviderAdministrator(
                required.organizationId(), required.teamId(), required.actor(), now);
        return mappings.findPage(new LarkMemberMappingPageRequest(
                required.organizationId(),
                required.teamId(),
                required.status(),
                required.after(),
                required.limit()));
    }

    /** Resolves the exact open_id only after rechecking member, mapping and provider authorization. */
    public CollaborationRecipient resolveRecipient(
            OrganizationId organizationId,
            TeamId teamId,
            TeamMemberId memberId,
            ProviderBindingId providerBindingId) {
        TeamMember member = requireMember(organizationId, teamId, memberId);
        LarkMemberMapping mapping = mappings.findActiveByInternalKey(
                        new io.crewscope.domain.collaboration.LarkInternalMemberKey(
                                organizationId, teamId, memberId))
                .orElseThrow(() -> new DomainValidationException(
                        "collaborationRecipient", "has no active confirmed Lark mapping"));
        LarkConnectionAuthorization authorization = authorization(
                organizationId,
                teamId,
                providerBindingId,
                LarkCollaborationCapabilities.NOTIFICATION_DELIVERY);
        LarkExternalTenant tenant = requireTenant(organizationId, mapping.externalTenantId());
        return mapping.resolveRecipient(member, authorization, tenant);
    }

    private LarkExternalTenant upsertTenant(
            LarkConnectionAuthorization authorization, LarkTenantObservation observation) {
        Optional<LarkExternalTenant> existing = tenants.findByConnection(
                authorization.organizationId(), authorization.connectionId());
        if (existing.isEmpty()) {
            return tenants.create(LarkExternalTenant.verify(
                    authorization,
                    observation.tenantKey(),
                    observation.providerVersion(),
                    observation.observedAt()));
        }
        LarkExternalTenant current = existing.orElseThrow();
        LarkExternalTenant refreshed = current.refresh(
                current.version(),
                authorization,
                observation.tenantKey(),
                observation.providerVersion(),
                observation.observedAt());
        return refreshed == current ? current : tenants.update(refreshed);
    }

    private LarkConnectionAuthorization authorization(
            OrganizationId organizationId,
            TeamId teamId,
            ProviderBindingId providerBindingId,
            io.crewscope.domain.provider.ProviderCapabilities capabilities) {
        return authorizations.resolveCurrent(
                        organizationId, teamId, providerBindingId, capabilities)
                .requireCapabilities(capabilities);
    }

    private TeamMember requireMember(
            OrganizationId organizationId, TeamId teamId, TeamMemberId memberId) {
        return members.findById(organizationId, memberId)
                .filter(TeamMember::canParticipate)
                .filter(value -> value.scope().teamId().equals(teamId))
                .orElseThrow(() -> new DomainValidationException(
                        "larkMemberMapping.memberId", "must be an ACTIVE member in the exact Team"));
    }

    private LarkMemberVerificationProof requireProof(
            OrganizationId organizationId, LarkMemberVerificationProofId id) {
        return proofs.findById(organizationId, id)
                .orElseThrow(() -> new IllegalArgumentException("Lark verification proof was not found"));
    }

    private LarkExternalTenant requireTenant(
            OrganizationId organizationId,
            io.crewscope.domain.collaboration.LarkExternalTenantId id) {
        return tenants.findById(organizationId, id)
                .orElseThrow(() -> new IllegalArgumentException("Lark external tenant was not found"));
    }

    private static Duration requireWindow(Duration value) {
        Duration required = Objects.requireNonNull(value, "confirmationWindow");
        if (required.isZero()
                || required.isNegative()
                || required.compareTo(LarkMemberVerificationProof.MAX_CONFIRMATION_WINDOW) > 0) {
            throw new IllegalArgumentException("Lark confirmation window must be within 15 minutes");
        }
        return required;
    }

    private static UtcTimestamp plus(UtcTimestamp value, Duration duration) {
        return UtcTimestamp.from(value.value().plus(duration));
    }
}
