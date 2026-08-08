package io.crewscope.infrastructure.persistence.workitem;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Scalar JPA snapshot for a WorkProject. */
@Entity
@Table(name = "work_project", schema = "crewscope")
public class WorkProjectEntity {
    @Id private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "team_id", nullable = false)
    private UUID teamId;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "project_key", nullable = false)
    private String projectKey;

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

    protected WorkProjectEntity() {}

    WorkProjectEntity(
            UUID id,
            UUID organizationId,
            UUID teamId,
            UUID workspaceId,
            String projectKey,
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
        this.workspaceId = workspaceId;
        this.projectKey = projectKey;
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

    UUID workspaceId() {
        return workspaceId;
    }

    String projectKey() {
        return projectKey;
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
