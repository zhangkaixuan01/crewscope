package io.crewscope.infrastructure.persistence.workgraph;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Scalar immutable persistence fact for one WorkItemRelation edge. */
@Entity
@Table(name = "work_item_relation", schema = "crewscope")
public class WorkItemRelationEntity {
    @Id private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "team_id", nullable = false)
    private UUID teamId;

    @Column(name = "src_workspace_id", nullable = false)
    private UUID srcWorkspaceId;

    @Column(name = "src_project_id", nullable = false)
    private UUID srcProjectId;

    @Column(name = "src_work_item_id", nullable = false)
    private UUID srcWorkItemId;

    @Column(name = "dst_workspace_id", nullable = false)
    private UUID dstWorkspaceId;

    @Column(name = "dst_project_id", nullable = false)
    private UUID dstProjectId;

    @Column(name = "dst_work_item_id", nullable = false)
    private UUID dstWorkItemId;

    @Column(name = "relation_kind", nullable = false)
    private String relationKind;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by_principal_id", nullable = false)
    private UUID createdBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by_principal_id", nullable = false)
    private UUID updatedBy;

    protected WorkItemRelationEntity() {}

    WorkItemRelationEntity(
            UUID id,
            UUID organizationId,
            UUID teamId,
            UUID srcWorkspaceId,
            UUID srcProjectId,
            UUID srcWorkItemId,
            UUID dstWorkspaceId,
            UUID dstProjectId,
            UUID dstWorkItemId,
            String relationKind,
            Instant createdAt,
            UUID createdBy,
            Instant updatedAt,
            UUID updatedBy) {
        this.id = id;
        this.organizationId = organizationId;
        this.teamId = teamId;
        this.srcWorkspaceId = srcWorkspaceId;
        this.srcProjectId = srcProjectId;
        this.srcWorkItemId = srcWorkItemId;
        this.dstWorkspaceId = dstWorkspaceId;
        this.dstProjectId = dstProjectId;
        this.dstWorkItemId = dstWorkItemId;
        this.relationKind = relationKind;
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

    UUID srcWorkspaceId() {
        return srcWorkspaceId;
    }

    UUID srcProjectId() {
        return srcProjectId;
    }

    UUID srcWorkItemId() {
        return srcWorkItemId;
    }

    UUID dstWorkspaceId() {
        return dstWorkspaceId;
    }

    UUID dstProjectId() {
        return dstProjectId;
    }

    UUID dstWorkItemId() {
        return dstWorkItemId;
    }

    String relationKind() {
        return relationKind;
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
