package io.crewscope.infrastructure.persistence.collaboration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.collaboration.LarkMemberMappingPageRequest;
import io.crewscope.domain.collaboration.LarkCollaborationCapabilities;
import io.crewscope.domain.collaboration.LarkConnectionAuthorization;
import io.crewscope.domain.collaboration.LarkExternalTenant;
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
import io.crewscope.domain.collaboration.LarkVerificationSource;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.provider.ConnectionGrantId;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.CredentialId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** Real PostgreSQL lifecycle, uniqueness and keyset contract for the M6-I05 mapping adapter. */
@SpringBootTest(
        classes = JdbcLarkCollaborationRepositoryM6I05IntegrationTest.TestApplication.class,
        properties = {
            "spring.flyway.schemas=crewscope",
            "spring.flyway.default-schema=crewscope",
            "spring.flyway.create-schemas=true"
        })
class JdbcLarkCollaborationRepositoryM6I05IntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final UtcTimestamp NOW = UtcTimestamp.from(
            Instant.parse("2026-08-26T10:00:00Z"));

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JdbcLarkCollaborationRepositoryAdapter repository;

    private Scope scope;
    private LarkConnectionAuthorization authorization;
    private Principal administrator;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE TABLE crewscope.organization CASCADE");
        scope = Scope.generate();
        seed(scope);
        authorization = new LarkConnectionAuthorization(
                scope.organizationId,
                scope.teamId,
                scope.bindingId,
                0,
                scope.connectionId,
                0,
                scope.grantId,
                0,
                new LarkTenantKey("tenant_alpha"),
                LarkCollaborationCapabilities.COMPLETE);
        administrator = Principal.create(
                scope.principalId,
                PrincipalScope.team(scope.organizationId, scope.teamId),
                PrincipalType.USER,
                Optional.empty(),
                "Lark Administrator",
                Optional.empty(),
                PrincipalVisibility.TEAM,
                NOW);
    }

    @Test
    void persistsProofsMappingsReplacementPaginationAndStrongVersion() {
        LarkExternalTenant tenant = repository.create(LarkExternalTenant.verify(
                authorization,
                authorization.expectedTenantKey(),
                new LarkProviderVersion("tenant-v1"),
                NOW));
        LarkMemberVerificationProof proof = repository.create(
                LarkMemberVerificationProof.verified(
                        LarkMemberVerificationProofId.generate(),
                        authorization,
                        tenant,
                        new LarkOpenId("ou_member_one"),
                        new LarkOpenId("ou_member_one"),
                        new LarkUnionId("on_member_one"),
                        new LarkProviderVersion("member-v1"),
                        NOW,
                        UtcTimestamp.from(NOW.value().plusSeconds(600))));
        LarkMemberMapping first = repository.createActive(mapping(
                LarkMemberMappingId.generate(),
                scope.memberId,
                "ou_member_one",
                "on_member_one",
                tenant));

        assertEquals(tenant.id(), repository.findByConnection(
                scope.organizationId, scope.connectionId).orElseThrow().id());
        assertEquals(proof.id(), repository.findById(
                scope.organizationId, proof.id()).orElseThrow().id());
        assertEquals(first.id(), repository.findActiveByInternalKey(
                first.internalKey()).orElseThrow().id());

        TeamMemberId secondMember = seedSecondMember();
        LarkMemberMapping second = repository.createActive(mapping(
                LarkMemberMappingId.generate(),
                secondMember,
                "ou_member_two",
                "on_member_two",
                tenant));
        var firstPage = repository.findPage(new LarkMemberMappingPageRequest(
                scope.organizationId,
                scope.teamId,
                Optional.of(LarkMemberMappingStatus.ACTIVE),
                Optional.empty(),
                1));
        var secondPage = repository.findPage(new LarkMemberMappingPageRequest(
                scope.organizationId,
                scope.teamId,
                Optional.of(LarkMemberMappingStatus.ACTIVE),
                firstPage.nextCursor(),
                1));
        assertEquals(1, firstPage.items().size());
        assertEquals(1, secondPage.items().size());
        assertTrue(firstPage.items().get(0).id().equals(first.id())
                || firstPage.items().get(0).id().equals(second.id()));

        LarkMemberMapping terminated = first.terminate(
                first.version(),
                LarkMemberMappingStatus.INVALIDATED,
                LarkMemberMappingTerminalReason.AUTHORIZATION_DRIFT,
                administrator,
                UtcTimestamp.from(NOW.value().plusSeconds(1)));
        LarkMemberMapping replacement = mapping(
                LarkMemberMappingId.generate(),
                scope.memberId,
                "ou_member_one",
                "on_member_one",
                tenant);
        repository.replaceActive(terminated, replacement);
        assertEquals(replacement.id(), repository.findActiveByInternalKey(
                replacement.internalKey()).orElseThrow().id());
        assertThrows(OptimisticLockConflictException.class, () -> repository.update(terminated));

        LarkMemberMapping conflicting = mapping(
                LarkMemberMappingId.generate(),
                seedSecondMember(),
                "ou_member_one",
                "on_member_one",
                tenant);
        assertThrows(DomainValidationException.class,
                () -> repository.createActive(conflicting));
    }

    private LarkMemberMapping mapping(
            LarkMemberMappingId id,
            TeamMemberId memberId,
            String openId,
            String unionId,
            LarkExternalTenant tenant) {
        return LarkMemberMapping.reconstitute(
                id,
                scope.organizationId,
                scope.teamId,
                memberId,
                scope.bindingId,
                0,
                scope.connectionId,
                0,
                scope.grantId,
                0,
                tenant.id(),
                tenant.version(),
                tenant.tenantKey(),
                new LarkOpenId(openId),
                new LarkUnionId(unionId),
                new LarkProviderVersion("member-v1"),
                LarkVerificationSource.LARK_OPEN_API_EXACT_OPEN_ID,
                NOW,
                scope.principalId,
                LarkMemberMappingStatus.ACTIVE,
                Optional.empty(),
                0,
                AuditMetadata.createdBy(scope.principalId, NOW));
    }

    private TeamMemberId seedSecondMember() {
        PrincipalId principalId = new PrincipalId(UUID.randomUUID());
        TeamMemberId memberId = new TeamMemberId(UUID.randomUUID());
        jdbc.update("""
                INSERT INTO crewscope.principal (
                    id, organization_id, team_id, principal_type,
                    display_name, visibility, status)
                VALUES (?, ?, ?, 'USER', 'Second Member', 'TEAM', 'ACTIVE')
                """, principalId.value(), scope.organizationId.value(), scope.teamId.value());
        jdbc.update("""
                INSERT INTO crewscope.team_member (
                    id, organization_id, team_id, user_principal_id,
                    status, join_method, joined_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', 'INVITATION', ?)
                """, memberId.value(), scope.organizationId.value(), scope.teamId.value(),
                principalId.value(), NOW.toOffsetDateTime());
        return memberId;
    }

    private void seed(Scope value) {
        jdbc.update("INSERT INTO crewscope.organization (id, name, status) VALUES (?, 'I05 Org', 'ACTIVE')",
                value.organizationId.value());
        jdbc.update("INSERT INTO crewscope.team (id, organization_id, name, status) VALUES (?, ?, 'I05 Team', 'ACTIVE')",
                value.teamId.value(), value.organizationId.value());
        jdbc.update("""
                INSERT INTO crewscope.principal (
                    id, organization_id, team_id, principal_type,
                    display_name, visibility, status)
                VALUES (?, ?, ?, 'USER', 'I05 Admin', 'TEAM', 'ACTIVE')
                """, value.principalId.value(), value.organizationId.value(), value.teamId.value());
        jdbc.update("""
                INSERT INTO crewscope.team_member (
                    id, organization_id, team_id, user_principal_id,
                    status, join_method, joined_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', 'BOOTSTRAP', ?)
                """, value.memberId.value(), value.organizationId.value(), value.teamId.value(),
                value.principalId.value(), NOW.toOffsetDateTime());
        jdbc.update("""
                INSERT INTO crewscope.workspace (
                    id, organization_id, team_id, workspace_type,
                    owner_principal_id, name, status)
                VALUES (?, ?, ?, 'TEAM', ?, 'Default', 'ACTIVE')
                """, value.workspaceId.value(), value.organizationId.value(), value.teamId.value(),
                value.principalId.value());
        jdbc.update("UPDATE crewscope.team SET owner_member_id = ?, default_workspace_id = ? WHERE id = ?",
                value.memberId.value(), value.workspaceId.value(), value.teamId.value());
        jdbc.update("""
                INSERT INTO crewscope.credential_secret (
                    id, organization_id, team_id, subject_type, subject_id, credential_key,
                    provider_key, credential_type, ciphertext, nonce, authentication_tag,
                    key_id, algorithm, aad_version, status)
                VALUES (?, ?, ?, 'TEAM', ?, 'lark-i05', 'lark-collaboration',
                    'LARK_APP_CREDENTIAL', DECODE('01','hex'), DECODE(?, 'hex'),
                    DECODE(?, 'hex'), 'test-key', 'AES-256-GCM', '1', 'ACTIVE')
                """, value.credentialId.value(), value.organizationId.value(), value.teamId.value(),
                value.teamId.value(), "00".repeat(12), "00".repeat(16));
        jdbc.update("""
                INSERT INTO crewscope.provider_definition (
                    id, organization_id, provider_key, provider_type, interface_version,
                    display_name, capabilities, status,
                    created_by_principal_id, updated_by_principal_id)
                VALUES (?, ?, 'lark-collaboration', 'COLLABORATION', '1.0.0', 'Lark',
                    ?::JSONB, 'ACTIVE', ?, ?)
                """, value.definitionId, value.organizationId.value(), capabilities(),
                value.principalId.value(), value.principalId.value());
        jdbc.update("""
                INSERT INTO crewscope.provider_implementation (
                    id, organization_id, provider_definition_id, provider_type,
                    definition_interface_version, implementation_key, implementation_version,
                    capabilities, connection_requirement, connector_key, status,
                    created_by_principal_id, updated_by_principal_id)
                VALUES (?, ?, ?, 'COLLABORATION', '1.0.0', 'lark-collaboration', '1.0.0',
                    ?::JSONB, 'REQUIRED', 'lark-collaboration', 'ACTIVE', ?, ?)
                """, value.implementationId, value.organizationId.value(), value.definitionId,
                capabilities(), value.principalId.value(), value.principalId.value());
        jdbc.update("""
                INSERT INTO crewscope.connection (
                    id, organization_id, owner_type, owner_id, owner_team_id, connector_key,
                    external_account_reference, credential_id, status,
                    created_by_principal_id, updated_by_principal_id)
                VALUES (?, ?, 'TEAM', ?, ?, 'lark-collaboration', 'tenant_alpha', ?, 'ACTIVE', ?, ?)
                """, value.connectionId.value(), value.organizationId.value(), value.teamId.value(),
                value.teamId.value(), value.credentialId.value(), value.principalId.value(),
                value.principalId.value());
        jdbc.update("""
                INSERT INTO crewscope.connection_grant (
                    id, organization_id, connection_id,
                    connection_owner_type, connection_owner_id,
                    grantee_type, grantee_id, grantee_team_id,
                    granted_capabilities, resource_unrestricted, granted_resources,
                    valid_from, status, created_by_principal_id, updated_by_principal_id)
                VALUES (?, ?, ?, 'TEAM', ?, 'TEAM', ?, ?, ?::JSONB, TRUE, '[]'::JSONB,
                    ?, 'ACTIVE', ?, ?)
                """, value.grantId.value(), value.organizationId.value(), value.connectionId.value(),
                value.teamId.value(), value.teamId.value(), value.teamId.value(), capabilities(),
                NOW.toOffsetDateTime(), value.principalId.value(),
                value.principalId.value());
        jdbc.update("""
                INSERT INTO crewscope.provider_binding (
                    id, organization_id, team_id, workspace_id, target_type,
                    owner_type, owner_id, owner_team_id,
                    provider_definition_id, provider_definition_version, provider_type,
                    provider_implementation_id, provider_implementation_version,
                    connection_requirement, connection_id, connection_version,
                    connection_grant_id, connection_grant_version, execution_identity,
                    effective_capabilities, resource_unrestricted, effective_resources,
                    default_usage, status, created_by_principal_id, updated_by_principal_id)
                VALUES (?, ?, ?, ?, 'WORKSPACE', 'TEAM', ?, ?, ?, 0, 'COLLABORATION', ?, 0,
                    'REQUIRED', ?, 0, ?, 0, 'TEAM_SERVICE_ACCOUNT', ?::JSONB, TRUE,
                    '[]'::JSONB, TRUE, 'ACTIVE', ?, ?)
                """, value.bindingId.value(), value.organizationId.value(), value.teamId.value(),
                value.workspaceId.value(), value.teamId.value(), value.teamId.value(),
                value.definitionId, value.implementationId, value.connectionId.value(),
                value.grantId.value(), capabilities(), value.principalId.value(),
                value.principalId.value());
    }

    private static String capabilities() {
        return "[\"" + LarkCollaborationCapabilities.MEMBER_LOOKUP + "\",\""
                + LarkCollaborationCapabilities.FIXED_TEMPLATE_NOTIFY + "\"]";
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(JdbcLarkCollaborationRepositoryAdapter.class)
    static class TestApplication {}

    private record Scope(
            OrganizationId organizationId,
            TeamId teamId,
            WorkspaceId workspaceId,
            PrincipalId principalId,
            TeamMemberId memberId,
            CredentialId credentialId,
            UUID definitionId,
            UUID implementationId,
            ConnectionId connectionId,
            ConnectionGrantId grantId,
            ProviderBindingId bindingId) {

        private static Scope generate() {
            return new Scope(
                    OrganizationId.generate(),
                    TeamId.generate(),
                    WorkspaceId.generate(),
                    new PrincipalId(UUID.randomUUID()),
                    new TeamMemberId(UUID.randomUUID()),
                    new CredentialId(UUID.randomUUID()),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    new ConnectionId(UUID.randomUUID()),
                    new ConnectionGrantId(UUID.randomUUID()),
                    new ProviderBindingId(UUID.randomUUID()));
        }
    }
}
