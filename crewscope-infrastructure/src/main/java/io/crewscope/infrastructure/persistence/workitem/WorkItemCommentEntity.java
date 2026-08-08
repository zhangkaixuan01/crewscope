package io.crewscope.infrastructure.persistence.workitem;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Scalar immutable persistence fact for a WorkItem comment. */
@Entity
@Table(name = "work_item_comment", schema = "crewscope")
public class WorkItemCommentEntity {
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

    @Column(name = "author_principal_id", nullable = false)
    private UUID authorPrincipalId;

    @Column(nullable = false)
    private String content;

    @Column(name = "source_provider", nullable = false)
    private String sourceProvider;

    @Column(name = "external_id")
    private String externalId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by_principal_id", nullable = false)
    private UUID createdBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by_principal_id", nullable = false)
    private UUID updatedBy;

    protected WorkItemCommentEntity() {}

    WorkItemCommentEntity(
            UUID id,
            UUID organizationId,
            UUID teamId,
            UUID workspaceId,
            UUID projectId,
            UUID workItemId,
            UUID authorPrincipalId,
            String content,
            String sourceProvider,
            String externalId,
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
        this.authorPrincipalId = authorPrincipalId;
        this.content = content;
        this.sourceProvider = sourceProvider;
        this.externalId = externalId;
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

    UUID authorPrincipalId() {
        return authorPrincipalId;
    }

    String content() {
        return content;
    }

    String sourceProvider() {
        return sourceProvider;
    }

    String externalId() {
        return externalId;
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
