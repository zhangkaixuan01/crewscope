package io.crewscope.domain.organization;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;

/**
 * The Organization aggregate root (ADR-035).
 *
 * <p>The table has existed since V1; M12-D01 materializes the aggregate with the six existing
 * columns as the whole state surface — zero new columns, no hosted-tenancy fields reserved. There
 * is deliberately no creation factory and no mutating command yet: on the single-organization
 * profile the bootstrap seeder remains the only organization writer, and lifecycle commands (if
 * ever) belong to the hosted increment under an explicit ADR-035 revision. A01 consumes this
 * aggregate for organization settings; D01's consumers are the persistence port and its tests.
 */
public final class Organization {

    /** Aligns with the V1 column {@code name VARCHAR(200) NOT NULL}. */
    static final int NAME_MAX_LENGTH = 200;

    private final OrganizationId id;
    private final String name;
    private final OrganizationStatus status;
    private final long version;
    private final UtcTimestamp createdAt;
    private final UtcTimestamp updatedAt;

    private Organization(
            OrganizationId id,
            String name,
            OrganizationStatus status,
            long version,
            UtcTimestamp createdAt,
            UtcTimestamp updatedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = requireName(name);
        this.status = Objects.requireNonNull(status, "status");
        if (version < 0) {
            throw new DomainValidationException("organization.version", "must not be negative");
        }
        this.version = version;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /** Restores a persisted organization; enforces the same invariants as the table shape. */
    public static Organization reconstitute(
            OrganizationId id,
            String name,
            OrganizationStatus status,
            long version,
            UtcTimestamp createdAt,
            UtcTimestamp updatedAt) {
        return new Organization(id, name, status, version, createdAt, updatedAt);
    }

    public OrganizationId id() {
        return id;
    }

    public String name() {
        return name;
    }

    public OrganizationStatus status() {
        return status;
    }

    public long version() {
        return version;
    }

    public UtcTimestamp createdAt() {
        return createdAt;
    }

    public UtcTimestamp updatedAt() {
        return updatedAt;
    }

    private static String requireName(String name) {
        if (name == null) {
            throw new DomainValidationException("organization.name", "must not be null");
        }
        String trimmed = name.strip();
        if (trimmed.isEmpty()) {
            throw new DomainValidationException("organization.name", "must not be blank");
        }
        if (trimmed.length() > NAME_MAX_LENGTH) {
            throw new DomainValidationException(
                    "organization.name", "must not exceed " + NAME_MAX_LENGTH + " characters");
        }
        return trimmed;
    }
}
