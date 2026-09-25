package io.crewscope.infrastructure.persistence.responsibility;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Scalar JPA snapshot for one queued handover item (V45). */
@Entity
@Table(name = "responsibility_handover_item", schema = "crewscope")
public class ResponsibilityHandoverItemEntity {
    @Id private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(name = "assignment_id", nullable = false)
    private UUID assignmentId;

    @Column(name = "work_item_id", nullable = false)
    private UUID workItemId;

    @Column(name = "team_id", nullable = false)
    private UUID teamId;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "expected_assignment_version", nullable = false)
    private long expectedAssignmentVersion;

    @Column(nullable = false)
    private String state;

    @Column(name = "result_assignment_id")
    private UUID resultAssignmentId;

    @Column(name = "error_code")
    private String errorCode;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by_principal_id", nullable = false)
    private UUID createdByPrincipalId;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by_principal_id", nullable = false)
    private UUID updatedByPrincipalId;

    protected ResponsibilityHandoverItemEntity() {}

    ResponsibilityHandoverItemEntity(
            UUID id,
            UUID organizationId,
            UUID jobId,
            UUID assignmentId,
            UUID workItemId,
            UUID teamId,
            UUID workspaceId,
            UUID projectId,
            long expectedAssignmentVersion,
            String state,
            UUID resultAssignmentId,
            String errorCode,
            Instant processedAt,
            long version,
            Instant createdAt,
            UUID createdByPrincipalId,
            Instant updatedAt,
            UUID updatedByPrincipalId) {
        this.id = id;
        this.organizationId = organizationId;
        this.jobId = jobId;
        this.assignmentId = assignmentId;
        this.workItemId = workItemId;
        this.teamId = teamId;
        this.workspaceId = workspaceId;
        this.projectId = projectId;
        this.expectedAssignmentVersion = expectedAssignmentVersion;
        this.state = state;
        this.resultAssignmentId = resultAssignmentId;
        this.errorCode = errorCode;
        this.processedAt = processedAt;
        this.version = version;
        this.createdAt = createdAt;
        this.createdByPrincipalId = createdByPrincipalId;
        this.updatedAt = updatedAt;
        this.updatedByPrincipalId = updatedByPrincipalId;
    }

    UUID id() {
        return id;
    }

    UUID organizationId() {
        return organizationId;
    }

    UUID jobId() {
        return jobId;
    }

    UUID assignmentId() {
        return assignmentId;
    }

    UUID workItemId() {
        return workItemId;
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

    long expectedAssignmentVersion() {
        return expectedAssignmentVersion;
    }

    String state() {
        return state;
    }

    UUID resultAssignmentId() {
        return resultAssignmentId;
    }

    String errorCode() {
        return errorCode;
    }

    Instant processedAt() {
        return processedAt;
    }

    long version() {
        return version;
    }

    Instant createdAt() {
        return createdAt;
    }

    UUID createdByPrincipalId() {
        return createdByPrincipalId;
    }

    Instant updatedAt() {
        return updatedAt;
    }

    UUID updatedByPrincipalId() {
        return updatedByPrincipalId;
    }
}
