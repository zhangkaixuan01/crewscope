package io.crewscope.infrastructure.persistence.team;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Scalar JPA snapshot for one explicit member role grant. */
@Entity
@Table(name = "team_member_role", schema = "crewscope")
public class MemberRoleEntity {
    @Id private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "team_id", nullable = false)
    private UUID teamId;

    @Column(name = "team_member_id", nullable = false)
    private UUID teamMemberId;

    @Column(name = "team_role_id", nullable = false)
    private UUID teamRoleId;

    @Column(name = "scope_type", nullable = false)
    private String scopeType;

    @Column(name = "scope_id")
    private UUID scopeId;

    @Column(name = "granted_by_principal_id", nullable = false)
    private UUID grantedBy;

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MemberRoleEntity() {}

    MemberRoleEntity(
            UUID id,
            UUID organizationId,
            UUID teamId,
            UUID teamMemberId,
            UUID teamRoleId,
            String scopeType,
            UUID scopeId,
            UUID grantedBy,
            Instant grantedAt,
            Instant validFrom,
            Instant expiresAt,
            Instant revokedAt,
            String status,
            long version,
            Instant createdAt,
            Instant updatedAt) {
        this.id = id;
        this.organizationId = organizationId;
        this.teamId = teamId;
        this.teamMemberId = teamMemberId;
        this.teamRoleId = teamRoleId;
        this.scopeType = scopeType;
        this.scopeId = scopeId;
        this.grantedBy = grantedBy;
        this.grantedAt = grantedAt;
        this.validFrom = validFrom;
        this.expiresAt = expiresAt;
        this.revokedAt = revokedAt;
        this.status = status;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    UUID id() {
        return id;
    }

    UUID organizationId() {
        return organizationId;
    }

    UUID teamId() {
        return teamId;
    }

    UUID teamMemberId() {
        return teamMemberId;
    }

    UUID teamRoleId() {
        return teamRoleId;
    }

    String scopeType() {
        return scopeType;
    }

    UUID scopeId() {
        return scopeId;
    }

    UUID grantedBy() {
        return grantedBy;
    }

    Instant grantedAt() {
        return grantedAt;
    }

    Instant validFrom() {
        return validFrom;
    }

    Instant expiresAt() {
        return expiresAt;
    }

    Instant revokedAt() {
        return revokedAt;
    }

    String status() {
        return status;
    }

    long version() {
        return version;
    }

    Instant createdAt() {
        return createdAt;
    }

    Instant updatedAt() {
        return updatedAt;
    }
}
