package io.crewscope.infrastructure.persistence.workitem;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Scalar immutable persistence fact for a linked WorkItem resource. */
@Entity
@Table(name = "work_item_resource_link", schema = "crewscope")
public class WorkItemResourceLinkEntity {
    @Id private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "team_id", nullable = false)
    private UUID teamId;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "work_item_id", nullable = false)
    private UUID workItemId;

    @Column(name = "resource_type", nullable = false)
    private String resourceType;

    @Column(name = "resource_reference", nullable = false)
    private String resourceReference;

    private String label;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by_principal_id", nullable = false)
    private UUID createdBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by_principal_id", nullable = false)
    private UUID updatedBy;

    protected WorkItemResourceLinkEntity() {}

    WorkItemResourceLinkEntity(
            UUID id,
            UUID organizationId,
            UUID teamId,
            UUID workspaceId,
            UUID projectId,
            UUID workItemId,
            String resourceType,
            String resourceReference,
            String label,
            Instant createdAt,
            UUID createdBy,
            Instant updatedAt,
            UUID updatedBy) {
        this.id = id;
        this.organizationId = organizationId;
        this.teamId = teamId;
        this.workspaceId = workspaceId;
        this.projectId = projectId;
        this.workItemId = workItemId;
        this.resourceType = resourceType;
        this.resourceReference = resourceReference;
        this.label = label;
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

    UUID projectId() {
        return projectId;
    }

    UUID workItemId() {
        return workItemId;
    }

    String resourceType() {
        return resourceType;
    }

    String resourceReference() {
        return resourceReference;
    }

    String label() {
        return label;
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
