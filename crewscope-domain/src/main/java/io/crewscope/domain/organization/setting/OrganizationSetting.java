package io.crewscope.domain.organization.setting;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.Optional;

/**
 * Organization-level settings (V68): typed columns, not a key-value table
 * (notification_preference / project_execution_defaults precedent).
 *
 * <p>The default state is "no row": audit retention is forever until an administrator writes the
 * first row. The application surfaces that default without materializing an aggregate; this type
 * only exists once a row exists.
 */
public final class OrganizationSetting {

    /** Aligns with the V68 CHECK {@code audit_event_retention_days IS NULL OR >= 30}. */
    static final int MIN_RETENTION_DAYS = 30;

    private final OrganizationId organizationId;
    private final Integer auditEventRetentionDays;
    private final long version;
    private final UtcTimestamp createdAt;
    private final PrincipalId createdBy;
    private final UtcTimestamp updatedAt;
    private final PrincipalId updatedBy;

    private OrganizationSetting(
            OrganizationId organizationId,
            Integer auditEventRetentionDays,
            long version,
            UtcTimestamp createdAt,
            PrincipalId createdBy,
            UtcTimestamp updatedAt,
            PrincipalId updatedBy) {
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId");
        this.auditEventRetentionDays = requireRetentionDays(auditEventRetentionDays);
        if (version < 0) {
            throw new DomainValidationException("organizationSetting.version", "must not be negative");
        }
        this.version = version;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.updatedBy = Objects.requireNonNull(updatedBy, "updatedBy");
    }

    /** Materializes the first settings row; {@code retentionDays} null means retain forever. */
    public static OrganizationSetting initial(
            OrganizationId organizationId,
            Integer retentionDays,
            PrincipalId actor,
            UtcTimestamp occurredAt) {
        return new OrganizationSetting(
                organizationId, retentionDays, 0, occurredAt, actor, occurredAt, actor);
    }

    /** Restores a persisted settings row. */
    public static OrganizationSetting reconstitute(
            OrganizationId organizationId,
            Integer auditEventRetentionDays,
            long version,
            UtcTimestamp createdAt,
            PrincipalId createdBy,
            UtcTimestamp updatedAt,
            PrincipalId updatedBy) {
        return new OrganizationSetting(
                organizationId, auditEventRetentionDays, version, createdAt, createdBy,
                updatedAt, updatedBy);
    }

    /**
     * Changes the audit retention under optimistic versioning.
     *
     * @return the successor state at version {@code expectedVersion + 1}
     */
    public OrganizationSetting changeRetention(
            long expectedVersion, Integer retentionDays, PrincipalId actor, UtcTimestamp occurredAt) {
        if (expectedVersion != version) {
            throw new io.crewscope.domain.shared.error.OptimisticLockConflictException(
                    "ORGANIZATION_SETTING",
                    organizationId.value().toString(),
                    expectedVersion,
                    version);
        }
        return new OrganizationSetting(
                organizationId,
                retentionDays,
                expectedVersion + 1,
                createdAt,
                createdBy,
                occurredAt,
                actor);
    }

    public OrganizationId organizationId() {
        return organizationId;
    }

    /** Empty means retain forever (the default state's value). */
    public Optional<Integer> auditEventRetentionDays() {
        return Optional.ofNullable(auditEventRetentionDays);
    }

    public long version() {
        return version;
    }

    public UtcTimestamp createdAt() {
        return createdAt;
    }

    public PrincipalId createdBy() {
        return createdBy;
    }

    public UtcTimestamp updatedAt() {
        return updatedAt;
    }

    public PrincipalId updatedBy() {
        return updatedBy;
    }

    private static Integer requireRetentionDays(Integer retentionDays) {
        if (retentionDays == null) {
            return null;
        }
        if (retentionDays < MIN_RETENTION_DAYS) {
            throw new DomainValidationException(
                    "organizationSetting.auditEventRetentionDays",
                    "must not be lower than " + MIN_RETENTION_DAYS + " days");
        }
        return retentionDays;
    }
}
