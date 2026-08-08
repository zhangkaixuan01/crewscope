package io.crewscope.infrastructure.persistence.team;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Scalar JPA snapshot for the Principal half of a Personal Agent. */
@Entity
@Table(name = "principal", schema = "crewscope")
public class PrincipalEntity {
    @Id private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "team_id")
    private UUID teamId;

    @Column(name = "principal_type", nullable = false)
    private String type;

    @Column(name = "owner_principal_id")
    private UUID ownerPrincipalId;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "identity_provider")
    private String identityProvider;

    @Column(name = "external_subject")
    private String externalSubject;

    @Column(nullable = false)
    private String visibility;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PrincipalEntity() {}

    PrincipalEntity(
            UUID id,
            UUID organizationId,
            UUID teamId,
            String type,
            UUID ownerPrincipalId,
            String displayName,
            String identityProvider,
            String externalSubject,
            String visibility,
            String status,
            long version,
            Instant createdAt,
            Instant updatedAt) {
        this.id = id;
        this.organizationId = organizationId;
        this.teamId = teamId;
        this.type = type;
        this.ownerPrincipalId = ownerPrincipalId;
        this.displayName = displayName;
        this.identityProvider = identityProvider;
        this.externalSubject = externalSubject;
        this.visibility = visibility;
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

    String type() {
        return type;
    }

    UUID ownerPrincipalId() {
        return ownerPrincipalId;
    }

    String displayName() {
        return displayName;
    }

    String identityProvider() {
        return identityProvider;
    }

    String externalSubject() {
        return externalSubject;
    }

    String visibility() {
        return visibility;
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
