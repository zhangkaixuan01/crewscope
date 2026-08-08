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

/** Capability and resource delegation for an external Connection. */
@Entity
@Table(name = "connection_grant", schema = "crewscope")
class ConnectionGrantEntity {
    @Id UUID id;
    @Column(name = "organization_id", nullable = false) UUID organizationId;
    @Column(name = "connection_id", nullable = false) UUID connectionId;
    @Column(name = "connection_owner_type", nullable = false, length = 32) String connectionOwnerType;
    @Column(name = "connection_owner_id", nullable = false) UUID connectionOwnerId;
    @Column(name = "grantee_type", nullable = false, length = 32) String granteeType;
    @Column(name = "grantee_id", nullable = false) UUID granteeId;
    @Column(name = "grantee_team_id") UUID granteeTeamId;
    @Column(name = "grantee_user_principal_id") UUID granteeUserPrincipalId;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "granted_capabilities", nullable = false, columnDefinition = "jsonb") List<String> grantedCapabilities;
    @Column(name = "resource_unrestricted", nullable = false) boolean resourceUnrestricted;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "granted_resources", nullable = false, columnDefinition = "jsonb") List<String> grantedResources;
    @Column(name = "valid_from", nullable = false) Instant validFrom;
    @Column(name = "expires_at") Instant expiresAt;
    @Column(nullable = false, length = 32) String status;
    @Column(name = "terminal_reason") String terminalReason;
    @Version @Column(nullable = false) long version;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "created_by_principal_id", nullable = false) UUID createdByPrincipalId;
    @Column(name = "updated_at", nullable = false) Instant updatedAt;
    @Column(name = "updated_by_principal_id", nullable = false) UUID updatedByPrincipalId;
    protected ConnectionGrantEntity() {}
}
