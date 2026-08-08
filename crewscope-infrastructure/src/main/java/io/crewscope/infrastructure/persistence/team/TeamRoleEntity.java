package io.crewscope.infrastructure.persistence.team;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Scalar JPA snapshot for a Team role definition. */
@Entity
@Table(name = "team_role", schema = "crewscope")
public class TeamRoleEntity {
    @Id private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "team_id", nullable = false)
    private UUID teamId;

    @Column(name = "role_key", nullable = false)
    private String roleKey;

    @Column(nullable = false)
    private String name;

    private String description;

    @Column(name = "built_in", nullable = false)
    private boolean builtIn;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<String> permissions;

    @Column(name = "scope_type", nullable = false)
    private String scopeType;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TeamRoleEntity() {}

    TeamRoleEntity(
            UUID id,
            UUID organizationId,
            UUID teamId,
            String roleKey,
            String name,
            String description,
            boolean builtIn,
            List<String> permissions,
            String scopeType,
            String status,
            long version,
            Instant createdAt,
            Instant updatedAt) {
        this.id = id;
        this.organizationId = organizationId;
        this.teamId = teamId;
        this.roleKey = roleKey;
        this.name = name;
        this.description = description;
        this.builtIn = builtIn;
        this.permissions = List.copyOf(permissions);
        this.scopeType = scopeType;
        this.status = status;
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

    String roleKey() {
        return roleKey;
    }

    String name() {
        return name;
    }

    String description() {
        return description;
    }

    boolean builtIn() {
        return builtIn;
    }

    List<String> permissions() {
        return permissions;
    }

    String scopeType() {
        return scopeType;
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
}
