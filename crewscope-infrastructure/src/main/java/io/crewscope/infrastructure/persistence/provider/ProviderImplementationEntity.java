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

/** Concrete implementation pinned to one Provider contract interface. */
@Entity
@Table(name = "provider_implementation", schema = "crewscope")
class ProviderImplementationEntity {
    @Id UUID id;
    @Column(name = "organization_id", nullable = false) UUID organizationId;
    @Column(name = "provider_definition_id", nullable = false) UUID providerDefinitionId;
    @Column(name = "provider_type", nullable = false, length = 32) String providerType;
    @Column(name = "definition_interface_version", nullable = false, length = 64) String definitionInterfaceVersion;
    @Column(name = "implementation_key", nullable = false, length = 100) String implementationKey;
    @Column(name = "implementation_version", nullable = false, length = 64) String implementationVersion;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb") List<String> capabilities;
    @Column(name = "connection_requirement", nullable = false, length = 16) String connectionRequirement;
    @Column(name = "connector_key", length = 100) String connectorKey;
    @Column(nullable = false, length = 32) String status;
    @Version @Column(nullable = false) long version;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "created_by_principal_id", nullable = false) UUID createdByPrincipalId;
    @Column(name = "updated_at", nullable = false) Instant updatedAt;
    @Column(name = "updated_by_principal_id", nullable = false) UUID updatedByPrincipalId;
    protected ProviderImplementationEntity() {}
}
