package io.crewscope.infrastructure.persistence.provider;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** Versioned reference to an external identity and encrypted Credential. */
@Entity
@Table(name = "connection", schema = "crewscope")
class ConnectionEntity {
    @Id UUID id;
    @Column(name = "organization_id", nullable = false) UUID organizationId;
    @Column(name = "owner_type", nullable = false, length = 32) String ownerType;
    @Column(name = "owner_id", nullable = false) UUID ownerId;
    @Column(name = "owner_team_id") UUID ownerTeamId;
    @Column(name = "owner_user_principal_id") UUID ownerUserPrincipalId;
    @Column(name = "connector_key", nullable = false, length = 100) String connectorKey;
    @Column(name = "external_account_reference", nullable = false, length = 500) String externalAccountReference;
    @Column(name = "credential_id", nullable = false) UUID credentialId;
    @Column(nullable = false, length = 32) String status;
    @Column(name = "expires_at") Instant expiresAt;
    @Column(name = "terminal_reason") String terminalReason;
    @Version @Column(nullable = false) long version;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "created_by_principal_id", nullable = false) UUID createdByPrincipalId;
    @Column(name = "updated_at", nullable = false) Instant updatedAt;
    @Column(name = "updated_by_principal_id", nullable = false) UUID updatedByPrincipalId;
    protected ConnectionEntity() {}
}
