package io.crewscope.infrastructure.persistence.team;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Scalar JPA snapshot for Team membership and its lifecycle. */
@Entity
@Table(name = "team_member", schema = "crewscope")
public class TeamMemberEntity {
    @Id private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "team_id", nullable = false)
    private UUID teamId;

    @Column(name = "user_principal_id", nullable = false)
    private UUID userPrincipalId;

    @Column(nullable = false)
    private String status;

    @Column(name = "join_method", nullable = false)
    private String joinMethod;

    @Column(name = "invited_by_principal_id")
    private UUID invitedBy;

    @Column(name = "joined_at")
    private Instant joinedAt;

    @Column(name = "last_active_at")
    private Instant lastActiveAt;

    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TeamMemberEntity() {}

    TeamMemberEntity(
            UUID id,
            UUID organizationId,
            UUID teamId,
            UUID userPrincipalId,
            String status,
            String joinMethod,
            UUID invitedBy,
            Instant joinedAt,
            Instant lastActiveAt,
            long version,
            Instant createdAt,
            Instant updatedAt) {
        this.id = id;
        this.organizationId = organizationId;
        this.teamId = teamId;
        this.userPrincipalId = userPrincipalId;
        this.status = status;
        this.joinMethod = joinMethod;
        this.invitedBy = invitedBy;
        this.joinedAt = joinedAt;
        this.lastActiveAt = lastActiveAt;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
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

    UUID userPrincipalId() {
        return userPrincipalId;
    }

    String status() {
        return status;
    }

    String joinMethod() {
        return joinMethod;
    }

    UUID invitedBy() {
        return invitedBy;
    }

    Instant joinedAt() {
        return joinedAt;
    }

    Instant lastActiveAt() {
        return lastActiveAt;
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
}
