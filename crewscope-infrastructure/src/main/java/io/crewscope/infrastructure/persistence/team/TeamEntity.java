package io.crewscope.infrastructure.persistence.team;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Scalar JPA snapshot for a tenant-scoped Team. */
@Entity
@Table(name = "team", schema = "crewscope")
public class TeamEntity {
    @Id private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(nullable = false)
    private String name;

    @Column(name = "owner_member_id")
    private UUID ownerMemberId;

    @Column(name = "default_workspace_id")
    private UUID defaultWorkspaceId;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by_principal_id")
    private UUID createdBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by_principal_id")
    private UUID updatedBy;

    protected TeamEntity() {}

    TeamEntity(
            UUID id,
            UUID organizationId,
            String name,
            UUID ownerMemberId,
            UUID defaultWorkspaceId,
            String status,
            long version,
            Instant createdAt,
            UUID createdBy,
            Instant updatedAt,
            UUID updatedBy) {
        this.id = id;
        this.organizationId = organizationId;
        this.name = name;
        this.ownerMemberId = ownerMemberId;
        this.defaultWorkspaceId = defaultWorkspaceId;
        this.status = status;
        this.version = version;
        this.createdAt = createdAt;
        this.createdBy = createdBy;
        this.updatedAt = updatedAt;
        this.updatedBy = updatedBy;
    }

    UUID id() {
        return id;
    }

    UUID organizationId() {
        return organizationId;
    }

    String name() {
        return name;
    }

    UUID ownerMemberId() {
        return ownerMemberId;
    }

    UUID defaultWorkspaceId() {
        return defaultWorkspaceId;
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

    UUID createdBy() {
        return createdBy;
    }

    Instant updatedAt() {
        return updatedAt;
    }

    UUID updatedBy() {
        return updatedBy;
    }
}
