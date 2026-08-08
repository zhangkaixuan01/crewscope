package io.crewscope.infrastructure.persistence.team;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Scalar JPA snapshot for the product identity of an Agent. */
@Entity
@Table(name = "agent_profile", schema = "crewscope")
public class AgentProfileEntity {
    @Id private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "team_id", nullable = false)
    private UUID teamId;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "agent_principal_id", nullable = false)
    private UUID agentPrincipalId;

    @Column(name = "owner_member_id")
    private UUID ownerMemberId;

    @Column(name = "profile_type", nullable = false)
    private String type;

    @Column(name = "default_profile", nullable = false)
    private boolean defaultProfile;

    @Column(nullable = false)
    private String status;

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

    protected AgentProfileEntity() {}

    AgentProfileEntity(
            UUID id,
            UUID organizationId,
            UUID teamId,
            UUID workspaceId,
            UUID agentPrincipalId,
            UUID ownerMemberId,
            String type,
            boolean defaultProfile,
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
        this.agentPrincipalId = agentPrincipalId;
        this.ownerMemberId = ownerMemberId;
        this.type = type;
        this.defaultProfile = defaultProfile;
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

    UUID agentPrincipalId() {
        return agentPrincipalId;
    }

    UUID ownerMemberId() {
        return ownerMemberId;
    }

    String type() {
        return type;
    }

    boolean defaultProfile() {
        return defaultProfile;
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
