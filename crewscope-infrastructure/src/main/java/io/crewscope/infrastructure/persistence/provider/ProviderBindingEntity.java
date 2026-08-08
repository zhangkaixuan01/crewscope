package io.crewscope.infrastructure.persistence.provider;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Scope-closed, version-pinned Provider selection used by BindingResolver. */
@Entity
@Table(name = "provider_binding", schema = "crewscope")
class ProviderBindingEntity {
    @Id UUID id;
    @Column(name = "organization_id", nullable = false) UUID organizationId;
    @Column(name = "team_id", nullable = false) UUID teamId;
    @Column(name = "workspace_id", nullable = false) UUID workspaceId;
    @Column(name = "target_type", nullable = false, length = 32) String targetType;
    @Column(name = "work_project_id") UUID workProjectId;
    @Column(name = "owner_type", nullable = false, length = 32) String ownerType;
    @Column(name = "owner_id", nullable = false) UUID ownerId;
    @Column(name = "owner_team_id") UUID ownerTeamId;
    @Column(name = "owner_user_principal_id") UUID ownerUserPrincipalId;
    @Column(name = "provider_definition_id", nullable = false) UUID providerDefinitionId;
    @Column(name = "provider_definition_version", nullable = false) long providerDefinitionVersion;
    @Column(name = "provider_type", nullable = false, length = 32) String providerType;
    @Column(name = "provider_implementation_id", nullable = false) UUID providerImplementationId;
    @Column(name = "provider_implementation_version", nullable = false) long providerImplementationVersion;
    @Column(name = "connection_requirement", nullable = false, length = 16) String connectionRequirement;
    @Column(name = "connection_id") UUID connectionId;
    @Column(name = "connection_version") Long connectionVersion;
    @Column(name = "connection_grant_id") UUID connectionGrantId;
    @Column(name = "connection_grant_version") Long connectionGrantVersion;
    @Column(name = "execution_identity", length = 64) String executionIdentity;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "effective_capabilities", nullable = false, columnDefinition = "jsonb") List<String> effectiveCapabilities;
    @Column(name = "resource_unrestricted", nullable = false) boolean resourceUnrestricted;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "effective_resources", nullable = false, columnDefinition = "jsonb") List<String> effectiveResources;
    @Column(name = "default_usage", nullable = false) boolean defaultUsage;
    @Column(nullable = false, length = 32) String status;
    @Version @Column(nullable = false) long version;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "created_by_principal_id", nullable = false) UUID createdByPrincipalId;
    @Column(name = "updated_at", nullable = false) Instant updatedAt;
    @Column(name = "updated_by_principal_id", nullable = false) UUID updatedByPrincipalId;
    protected ProviderBindingEntity() {}
}
