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

/** Organization-scoped Provider contract registry row. */
@Entity
@Table(name = "provider_definition", schema = "crewscope")
class ProviderDefinitionEntity {
    @Id UUID id;
    @Column(name = "organization_id", nullable = false) UUID organizationId;
    @Column(name = "provider_key", nullable = false, length = 100) String providerKey;
    @Column(name = "provider_type", nullable = false, length = 32) String providerType;
    @Column(name = "interface_version", nullable = false, length = 64) String interfaceVersion;
    @Column(name = "display_name", nullable = false, length = 200) String displayName;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb") List<String> capabilities;
    @Column(nullable = false, length = 32) String status;
    @Version @Column(nullable = false) long version;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "created_by_principal_id", nullable = false) UUID createdByPrincipalId;
    @Column(name = "updated_at", nullable = false) Instant updatedAt;
    @Column(name = "updated_by_principal_id", nullable = false) UUID updatedByPrincipalId;
    protected ProviderDefinitionEntity() {}
}
