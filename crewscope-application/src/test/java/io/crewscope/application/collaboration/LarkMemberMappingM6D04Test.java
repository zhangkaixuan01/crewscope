package io.crewscope.application.collaboration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.application.team.TeamMemberRepository;
import io.crewscope.domain.collaboration.CollaborationRecipient;
import io.crewscope.domain.collaboration.LarkCollaborationCapabilities;
import io.crewscope.domain.collaboration.LarkConnectionAuthorization;
import io.crewscope.domain.collaboration.LarkExternalMemberKey;
import io.crewscope.domain.collaboration.LarkExternalTenant;
import io.crewscope.domain.collaboration.LarkExternalTenantId;
import io.crewscope.domain.collaboration.LarkInternalMemberKey;
import io.crewscope.domain.collaboration.LarkMemberMapping;
import io.crewscope.domain.collaboration.LarkMemberMappingId;
import io.crewscope.domain.collaboration.LarkMemberMappingStatus;
import io.crewscope.domain.collaboration.LarkMemberMappingTerminalReason;
import io.crewscope.domain.collaboration.LarkMemberVerificationProof;
import io.crewscope.domain.collaboration.LarkMemberVerificationProofId;
import io.crewscope.domain.collaboration.LarkOpenId;
import io.crewscope.domain.collaboration.LarkProviderVersion;
import io.crewscope.domain.collaboration.LarkTenantKey;
import io.crewscope.domain.collaboration.LarkUnionId;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.provider.ConnectionGrantId;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamJoinMethod;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.team.TeamScope;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LarkMemberMappingM6D04Test {

    private static final OrganizationId ORGANIZATION_ID =
            OrganizationId.from("00000000-0000-0000-0000-000000000751");
    private static final TeamId TEAM_ID =
            TeamId.from("00000000-0000-0000-0000-000000000752");
    private static final TeamMemberId MEMBER_ONE =
            TeamMemberId.from("00000000-0000-0000-0000-000000000753");
    private static final TeamMemberId MEMBER_TWO =
            TeamMemberId.from("00000000-0000-0000-0000-000000000754");
    private static final ProviderBindingId BINDING_ID = new ProviderBindingId(
            UUID.fromString("00000000-0000-0000-0000-000000000755"));
    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-25T10:00:00Z");

    private MutableTime time;
    private MutableAuthorizationResolver authorizationResolver;
    private RecordingAdministration administration;
    private RecordingVerification verification;
    private InMemoryTenants tenants;
    private InMemoryProofs proofs;
    private InMemoryMappings mappings;
    private InMemoryMembers members;
    private LarkMemberMappingApplicationService service;
    private Principal admin;

    @BeforeEach
    void setUp() {
        time = new MutableTime(NOW);
        authorizationResolver = new MutableAuthorizationResolver(authorization(1, 1, 1));
        administration = new RecordingAdministration();
        verification = new RecordingVerification();
        tenants = new InMemoryTenants();
        proofs = new InMemoryProofs();
        mappings = new InMemoryMappings();
        members = new InMemoryMembers();
        members.create(member(MEMBER_ONE,
                "00000000-0000-0000-0000-000000000756", "Member One"));
        members.create(member(MEMBER_TWO,
                "00000000-0000-0000-0000-000000000757", "Member Two"));
        admin = principal(
                "00000000-0000-0000-0000-000000000758", "Provider Admin");
        service = new LarkMemberMappingApplicationService(
                authorizationResolver,
                administration,
                verification,
                tenants,
                proofs,
                mappings,
                members,
                time,
                Duration.ofMinutes(10));
    }

    @Test
    void verifiesConfirmsAndResolvesOneExactRecipient() {
        LarkMemberVerificationProof proof = verify("ou_member_100");
        ConfirmLarkMemberMappingCommand command = confirm(MEMBER_ONE, proof.id());

        LarkMemberMapping mapping = service.confirmMapping(command);
        LarkMemberMapping replay = service.confirmMapping(command);
        CollaborationRecipient recipient = service.resolveRecipient(
                ORGANIZATION_ID, TEAM_ID, MEMBER_ONE, BINDING_ID);

        assertSame(mapping, replay);
        assertEquals(MEMBER_ONE, recipient.memberId());
        assertEquals(1, verification.tenantCalls);
        assertEquals(1, verification.memberCalls);
        assertEquals(1, mappings.byId.size());
        assertFalse((proof + " " + mapping + " " + recipient).contains("ou_member_100"));
    }

    @Test
    void rejectsTenantOrOpenIdMismatchWithoutPersistingProofOrMapping() {
        administration.allowed = false;
        assertThrows(DomainValidationException.class, () -> verify("ou_member_100"));
        assertEquals(0, verification.tenantCalls);
        administration.allowed = true;
        verification.tenantKey = new LarkTenantKey("tenant-b");
        assertThrows(DomainValidationException.class, () -> verify("ou_member_100"));
        assertEquals(0, proofs.byId.size());
        assertEquals(0, mappings.byId.size());

        verification.tenantKey = new LarkTenantKey("tenant-a");
        verification.returnDifferentOpenId = true;
        assertThrows(DomainValidationException.class, () -> verify("ou_member_100"));
        assertEquals(0, proofs.byId.size());
        assertThrows(DomainValidationException.class, () -> new LarkOpenId("display name"));
    }

    @Test
    void enforcesOneActiveInternalAndExternalIdentity() {
        LarkMemberVerificationProof first = verify("ou_member_100");
        service.confirmMapping(confirm(MEMBER_ONE, first.id()));

        LarkMemberVerificationProof otherIdentity = verify("ou_member_200");
        assertThrows(
                DomainValidationException.class,
                () -> service.confirmMapping(confirm(MEMBER_ONE, otherIdentity.id())));
        LarkMemberVerificationProof sameExternal = verify("ou_member_100");
        assertThrows(
                DomainValidationException.class,
                () -> service.confirmMapping(confirm(MEMBER_TWO, sameExternal.id())));
        assertEquals(1, mappings.activeInternal.size());
        assertEquals(1, mappings.activeExternal.size());
    }

    @Test
    void rejectsLateOrAuthorizationDriftedProof() {
        LarkMemberVerificationProof proof = verify("ou_member_100");
        time.now = UtcTimestamp.parse("2026-08-25T10:10:00Z");
        assertThrows(
                DomainValidationException.class,
                () -> service.confirmMapping(confirm(MEMBER_ONE, proof.id())));

        time.now = NOW;
        LarkMemberVerificationProof current = verify("ou_member_100");
        authorizationResolver.current = authorization(1, 2, 1);
        assertThrows(
                DomainValidationException.class,
                () -> service.confirmMapping(confirm(MEMBER_ONE, current.id())));
        assertEquals(0, mappings.byId.size());
    }

    @Test
    void providerVersionRefreshRequiresAndSupportsAtomicReconfirmation() {
        LarkMemberVerificationProof firstProof = verify("ou_member_100");
        LarkMemberMapping first = service.confirmMapping(confirm(MEMBER_ONE, firstProof.id()));

        verification.tenantProviderVersion = new LarkProviderVersion("tenant-v2");
        verification.memberProviderVersion = new LarkProviderVersion("member-v2");
        LarkMemberVerificationProof refreshedProof = verify("ou_member_100");
        LarkMemberMapping refreshed = service.confirmMapping(
                confirm(MEMBER_ONE, refreshedProof.id()));

        assertNotEquals(first.id(), refreshed.id());
        assertEquals(
                LarkMemberMappingStatus.INVALIDATED,
                mappings.byId.get(first.id()).status());
        assertEquals(
                LarkMemberMappingTerminalReason.AUTHORIZATION_DRIFT,
                mappings.byId.get(first.id()).terminalReason().orElseThrow());
        assertEquals(LarkMemberMappingStatus.ACTIVE, refreshed.status());
        assertEquals(new LarkProviderVersion("member-v2"), refreshed.providerVersion());
        assertEquals(1, mappings.activeInternal.size());
        assertEquals(1, mappings.activeExternal.size());
        assertEquals(
                refreshed.id(),
                service.resolveRecipient(
                                ORGANIZATION_ID, TEAM_ID, MEMBER_ONE, BINDING_ID)
                        .mappingId());
    }

    @Test
    void connectionRevocationFailsBeforeAnyExternalLookupOrRecipientDisclosure() {
        LarkMemberVerificationProof proof = verify("ou_member_100");
        service.confirmMapping(confirm(MEMBER_ONE, proof.id()));
        authorizationResolver.available = false;

        assertThrows(DomainValidationException.class, () -> verify("ou_member_100"));
        assertThrows(
                DomainValidationException.class,
                () -> service.resolveRecipient(
                        ORGANIZATION_ID, TEAM_ID, MEMBER_ONE, BINDING_ID));
        assertEquals(1, verification.tenantCalls);
        assertEquals(1, verification.memberCalls);
    }

    @Test
    void revokedMappingCannotResolveAndUsesStrongVersion() {
        LarkMemberVerificationProof proof = verify("ou_member_100");
        LarkMemberMapping mapping = service.confirmMapping(confirm(MEMBER_ONE, proof.id()));

        LarkMemberMapping revoked = service.revokeMapping(new RevokeLarkMemberMappingCommand(
                ORGANIZATION_ID,
                mapping.id(),
                mapping.version(),
                LarkMemberMappingTerminalReason.MEMBER_LEFT,
                admin));

        assertEquals(LarkMemberMappingStatus.REVOKED, revoked.status());
        assertThrows(
                DomainValidationException.class,
                () -> service.resolveRecipient(
                        ORGANIZATION_ID, TEAM_ID, MEMBER_ONE, BINDING_ID));
        assertThrows(
                io.crewscope.domain.shared.error.OptimisticLockConflictException.class,
                () -> service.revokeMapping(new RevokeLarkMemberMappingCommand(
                        ORGANIZATION_ID,
                        mapping.id(),
                        mapping.version(),
                        LarkMemberMappingTerminalReason.ADMIN_REVOKED,
                        admin)));
    }

    private LarkMemberVerificationProof verify(String openId) {
        return service.verifyMember(new VerifyLarkMemberCommand(
                ORGANIZATION_ID, TEAM_ID, BINDING_ID, new LarkOpenId(openId), admin));
    }

    private ConfirmLarkMemberMappingCommand confirm(
            TeamMemberId memberId, LarkMemberVerificationProofId proofId) {
        return new ConfirmLarkMemberMappingCommand(
                ORGANIZATION_ID, TEAM_ID, memberId, BINDING_ID, proofId, admin);
    }

    private static LarkConnectionAuthorization authorization(
            long bindingVersion, long connectionVersion, long grantVersion) {
        return new LarkConnectionAuthorization(
                ORGANIZATION_ID,
                TEAM_ID,
                BINDING_ID,
                bindingVersion,
                new ConnectionId(
                        UUID.fromString("00000000-0000-0000-0000-000000000759")),
                connectionVersion,
                new ConnectionGrantId(
                        UUID.fromString("00000000-0000-0000-0000-000000000760")),
                grantVersion,
                new LarkTenantKey("tenant-a"),
                LarkCollaborationCapabilities.COMPLETE);
    }

    private static TeamMember member(TeamMemberId id, String principalId, String name) {
        return TeamMember.join(
                id,
                new TeamScope(ORGANIZATION_ID, TEAM_ID),
                principal(principalId, name),
                TeamJoinMethod.BOOTSTRAP,
                NOW);
    }

    private static Principal principal(String id, String name) {
        return Principal.create(
                PrincipalId.from(id),
                PrincipalScope.team(ORGANIZATION_ID, TEAM_ID),
                PrincipalType.USER,
                Optional.empty(),
                name,
                Optional.empty(),
                PrincipalVisibility.TEAM,
                NOW);
    }

    private static final class MutableTime implements TimeProvider {
        private UtcTimestamp now;

        private MutableTime(UtcTimestamp now) {
            this.now = now;
        }

        @Override
        public UtcTimestamp now() {
            return now;
        }
    }

    private static final class MutableAuthorizationResolver
            implements LarkConnectionAuthorizationResolver {
        private LarkConnectionAuthorization current;
        private boolean available = true;

        private MutableAuthorizationResolver(LarkConnectionAuthorization current) {
            this.current = current;
        }

        @Override
        public LarkConnectionAuthorization resolveCurrent(
                OrganizationId organizationId,
                TeamId teamId,
                ProviderBindingId providerBindingId,
                io.crewscope.domain.provider.ProviderCapabilities requiredCapabilities) {
            if (!available) {
                throw new DomainValidationException(
                        "larkConnectionAuthorization", "Connection or Grant is unavailable");
            }
            if (!current.organizationId().equals(organizationId)
                    || !current.teamId().equals(teamId)
                    || !current.providerBindingId().equals(providerBindingId)) {
                throw new DomainValidationException(
                        "larkConnectionAuthorization", "scope does not match");
            }
            return current.requireCapabilities(requiredCapabilities);
        }
    }

    private static final class RecordingAdministration implements LarkMappingAdministration {
        private boolean allowed = true;

        @Override
        public void requireProviderAdministrator(
                OrganizationId organizationId,
                TeamId teamId,
                Principal actor,
                UtcTimestamp occurredAt) {
            if (!allowed
                    || !ORGANIZATION_ID.equals(organizationId)
                    || !TEAM_ID.equals(teamId)
                    || !actor.canAct()) {
                throw new DomainValidationException(
                        "larkMapping.administrator", "requires PROVIDER_MANAGE");
            }
        }
    }

    private static final class RecordingVerification implements LarkIdentityVerificationPort {
        private LarkTenantKey tenantKey = new LarkTenantKey("tenant-a");
        private LarkProviderVersion tenantProviderVersion =
                new LarkProviderVersion("tenant-v1");
        private LarkProviderVersion memberProviderVersion =
                new LarkProviderVersion("member-v1");
        private boolean returnDifferentOpenId;
        private int tenantCalls;
        private int memberCalls;

        @Override
        public LarkTenantObservation verifyTenant(LarkConnectionAuthorization authorization) {
            tenantCalls++;
            return new LarkTenantObservation(
                    tenantKey, tenantProviderVersion, NOW);
        }

        @Override
        public LarkMemberObservation verifyMember(
                LarkConnectionAuthorization authorization,
                LarkExternalTenant tenant,
                LarkOpenId exactOpenId) {
            memberCalls++;
            LarkOpenId observed = returnDifferentOpenId
                    ? new LarkOpenId("ou_different_999")
                    : exactOpenId;
            return new LarkMemberObservation(
                    observed,
                    new LarkUnionId("on_" + exactOpenId.value().substring(3)),
                    memberProviderVersion,
                    NOW);
        }
    }

    private static final class InMemoryTenants implements LarkExternalTenantRepository {
        private final Map<LarkExternalTenantId, LarkExternalTenant> byId = new HashMap<>();
        private final Map<ConnectionId, LarkExternalTenant> byConnection = new HashMap<>();

        @Override
        public Optional<LarkExternalTenant> findById(
                OrganizationId organizationId, LarkExternalTenantId id) {
            return Optional.ofNullable(byId.get(id))
                    .filter(value -> value.organizationId().equals(organizationId));
        }

        @Override
        public Optional<LarkExternalTenant> findByConnection(
                OrganizationId organizationId, ConnectionId connectionId) {
            return Optional.ofNullable(byConnection.get(connectionId))
                    .filter(value -> value.organizationId().equals(organizationId));
        }

        @Override
        public LarkExternalTenant create(LarkExternalTenant tenant) {
            byId.put(tenant.id(), tenant);
            byConnection.put(tenant.connectionId(), tenant);
            return tenant;
        }

        @Override
        public LarkExternalTenant update(LarkExternalTenant tenant) {
            return create(tenant);
        }
    }

    private static final class InMemoryProofs implements LarkMemberVerificationProofRepository {
        private final Map<LarkMemberVerificationProofId, LarkMemberVerificationProof> byId =
                new HashMap<>();

        @Override
        public LarkMemberVerificationProof create(LarkMemberVerificationProof proof) {
            byId.put(proof.id(), proof);
            return proof;
        }

        @Override
        public Optional<LarkMemberVerificationProof> findById(
                OrganizationId organizationId, LarkMemberVerificationProofId id) {
            return Optional.ofNullable(byId.get(id))
                    .filter(value -> value.organizationId().equals(organizationId));
        }
    }

    private static final class InMemoryMappings implements LarkMemberMappingRepository {
        private final Map<LarkMemberMappingId, LarkMemberMapping> byId = new HashMap<>();
        private final Map<LarkInternalMemberKey, LarkMemberMapping> activeInternal = new HashMap<>();
        private final Map<LarkExternalMemberKey, LarkMemberMapping> activeExternal = new HashMap<>();

        @Override
        public Optional<LarkMemberMapping> findById(
                OrganizationId organizationId, LarkMemberMappingId id) {
            return Optional.ofNullable(byId.get(id))
                    .filter(value -> value.organizationId().equals(organizationId));
        }

        @Override
        public Optional<LarkMemberMapping> findActiveByInternalKey(LarkInternalMemberKey key) {
            return Optional.ofNullable(activeInternal.get(key));
        }

        @Override
        public Optional<LarkMemberMapping> findActiveByExternalKey(LarkExternalMemberKey key) {
            return Optional.ofNullable(activeExternal.get(key));
        }

        @Override
        public LarkMemberMapping createActive(LarkMemberMapping mapping) {
            if (activeInternal.containsKey(mapping.internalKey())
                    || activeExternal.containsKey(mapping.externalKey())) {
                throw new DomainValidationException(
                        "larkMemberMapping", "active unique identity conflict");
            }
            byId.put(mapping.id(), mapping);
            activeInternal.put(mapping.internalKey(), mapping);
            activeExternal.put(mapping.externalKey(), mapping);
            return mapping;
        }

        @Override
        public LarkMemberMapping replaceActive(
                LarkMemberMapping terminatedMapping, LarkMemberMapping replacementMapping) {
            update(terminatedMapping);
            return createActive(replacementMapping);
        }

        @Override
        public LarkMemberMapping update(LarkMemberMapping mapping) {
            LarkMemberMapping previous = byId.put(mapping.id(), mapping);
            if (previous == null) {
                throw new IllegalArgumentException("Mapping was not found");
            }
            activeInternal.remove(previous.internalKey());
            activeExternal.remove(previous.externalKey());
            if (mapping.status() == LarkMemberMappingStatus.ACTIVE) {
                activeInternal.put(mapping.internalKey(), mapping);
                activeExternal.put(mapping.externalKey(), mapping);
            }
            return mapping;
        }
    }

    private static final class InMemoryMembers implements TeamMemberRepository {
        private final Map<TeamMemberId, TeamMember> values = new HashMap<>();

        @Override
        public TeamMember create(TeamMember member) {
            values.put(member.id(), member);
            return member;
        }

        @Override
        public Optional<TeamMember> findById(
                OrganizationId organizationId, TeamMemberId id) {
            return Optional.ofNullable(values.get(id))
                    .filter(value -> value.scope().organizationId().equals(organizationId));
        }
    }
}
