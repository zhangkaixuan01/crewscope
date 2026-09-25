package io.crewscope.infrastructure.persistence.responsibility;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Scalar JPA snapshot for one responsibility handover job (V45). */
@Entity
@Table(name = "responsibility_handover_job", schema = "crewscope")
public class ResponsibilityHandoverJobEntity {
    @Id private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "team_id", nullable = false)
    private UUID teamId;

    @Column(name = "source_member_id", nullable = false)
    private UUID sourceMemberId;

    @Column(name = "target_principal_id", nullable = false)
    private UUID targetPrincipalId;

    @Column(nullable = false)
    private String role;

    @Column(name = "command_id", nullable = false)
    private String commandId;

    @Column(name = "created_by_principal_id", nullable = false)
    private UUID createdByPrincipalId;

    @Column(name = "source_authorization_version", nullable = false)
    private long sourceAuthorizationVersion;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by_principal_id", nullable = false)
    private UUID updatedByPrincipalId;

    protected ResponsibilityHandoverJobEntity() {}

    ResponsibilityHandoverJobEntity(
            UUID id,
            UUID organizationId,
            UUID teamId,
            UUID sourceMemberId,
            UUID targetPrincipalId,
            String role,
            String commandId,
            UUID createdByPrincipalId,
            long sourceAuthorizationVersion,
            String status,
            long version,
            Instant createdAt,
            Instant updatedAt,
            UUID updatedByPrincipalId) {
        this.id = id;
        this.organizationId = organizationId;
        this.teamId = teamId;
        this.sourceMemberId = sourceMemberId;
        this.targetPrincipalId = targetPrincipalId;
        this.role = role;
        this.commandId = commandId;
        this.createdByPrincipalId = createdByPrincipalId;
        this.sourceAuthorizationVersion = sourceAuthorizationVersion;
        this.status = status;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.updatedByPrincipalId = updatedByPrincipalId;
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

    UUID sourceMemberId() {
        return sourceMemberId;
    }

    UUID targetPrincipalId() {
        return targetPrincipalId;
    }

    String role() {
        return role;
    }

    String commandId() {
        return commandId;
    }

    UUID createdByPrincipalId() {
        return createdByPrincipalId;
    }

    long sourceAuthorizationVersion() {
        return sourceAuthorizationVersion;
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

    UUID updatedByPrincipalId() {
        return updatedByPrincipalId;
    }
}
