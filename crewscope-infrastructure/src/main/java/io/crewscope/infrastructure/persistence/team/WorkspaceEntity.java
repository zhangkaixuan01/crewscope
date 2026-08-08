package io.crewscope.infrastructure.persistence.team;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Scalar JPA snapshot for a product Workspace. */
@Entity
@Table(name = "workspace", schema = "crewscope")
public class WorkspaceEntity {
    @Id private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "team_id")
    private UUID teamId;

    @Column(name = "workspace_type", nullable = false)
    private String type;

    @Column(name = "owner_principal_id")
    private UUID ownerPrincipalId;

    @Column(nullable = false)
    private String name;

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

    protected WorkspaceEntity() {}

    WorkspaceEntity(
            UUID id,
            UUID organizationId,
            UUID teamId,
            String type,
            UUID ownerPrincipalId,
            String name,
            String status,
            long version,
            Instant createdAt,
            UUID createdBy,
            Instant updatedAt,
            UUID updatedBy) {
        this.id = id;
        this.organizationId = organizationId;
        this.teamId = teamId;
        this.type = type;
        this.ownerPrincipalId = ownerPrincipalId;
        this.name = name;
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

    UUID teamId() {
        return teamId;
    }

    String type() {
        return type;
    }

    UUID ownerPrincipalId() {
        return ownerPrincipalId;
    }

    String name() {
        return name;
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
