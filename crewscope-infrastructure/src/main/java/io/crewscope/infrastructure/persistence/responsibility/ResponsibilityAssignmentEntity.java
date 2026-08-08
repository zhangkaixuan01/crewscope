package io.crewscope.infrastructure.persistence.responsibility;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Scalar JPA snapshot for one responsibility assignment fact. */
@Entity
@Table(name = "responsibility_assignment", schema = "crewscope")
public class ResponsibilityAssignmentEntity {
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

    @Column(nullable = false)
    private String role;

    @Column(name = "actor_principal_id", nullable = false)
    private UUID actorPrincipalId;

    @Column(name = "actor_type", nullable = false)
    private String actorType;

    @Column(name = "actor_member_id")
    private UUID actorMemberId;

    @Column(nullable = false)
    private String status;

    @Column(name = "assigned_by_principal_id", nullable = false)
    private UUID assignedBy;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt;

    @Column(name = "accepted_at", nullable = false)
    private Instant acceptedAt;

    @Column(name = "released_by_principal_id")
    private UUID releasedBy;

    @Column(name = "released_at")
    private Instant releasedAt;

    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by_principal_id", nullable = false)
    private UUID createdBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by_principal_id", nullable = false)
    private UUID updatedBy;

    protected ResponsibilityAssignmentEntity() {}

    ResponsibilityAssignmentEntity(
            UUID id,
            UUID organizationId,
            UUID teamId,
            UUID workspaceId,
            UUID projectId,
            UUID workItemId,
            String role,
            UUID actorPrincipalId,
            String actorType,
            UUID actorMemberId,
            String status,
            UUID assignedBy,
            Instant assignedAt,
            Instant acceptedAt,
            UUID releasedBy,
            Instant releasedAt,
            long version,
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
        this.role = role;
        this.actorPrincipalId = actorPrincipalId;
        this.actorType = actorType;
        this.actorMemberId = actorMemberId;
        this.status = status;
        this.assignedBy = assignedBy;
        this.assignedAt = assignedAt;
        this.acceptedAt = acceptedAt;
        this.releasedBy = releasedBy;
        this.releasedAt = releasedAt;
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

    UUID projectId() {
        return projectId;
    }

    UUID workItemId() {
        return workItemId;
    }

    String role() {
        return role;
    }

    UUID actorPrincipalId() {
        return actorPrincipalId;
    }

    String actorType() {
        return actorType;
    }

    UUID actorMemberId() {
        return actorMemberId;
    }

    String status() {
        return status;
    }

    UUID assignedBy() {
        return assignedBy;
    }

    Instant assignedAt() {
        return assignedAt;
    }

    Instant acceptedAt() {
        return acceptedAt;
    }

    UUID releasedBy() {
        return releasedBy;
    }

    Instant releasedAt() {
        return releasedAt;
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
