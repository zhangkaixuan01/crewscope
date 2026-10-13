package io.crewscope.domain.organization.quota;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Organization quota configuration (V70): typed columns per dimension, not a key-value table
 * (organization_setting / notification_preference precedent).
 *
 * <p>The default state is "no row": every dimension is unconfigured and the admission path skips
 * the organization entirely (zero-overhead freeze, S01 §3.3). A null column means that dimension
 * stays unconfigured even after the row exists; the intake limit and the token budget enforce
 * {@code >= 1} because a zero or negative limit is a configuration error, never a "block
 * everything" switch (that is a policy decision expressed by an explicit low limit).
 */
public final class OrganizationQuotaConfiguration {

    /** Aligns with the V70 CHECKs {@code ... IS NULL OR >= 1}. */
    static final long MIN_LIMIT = 1;

    private final OrganizationId organizationId;
    private final Integer monthlyTaskIntakeLimit;
    private final Long monthlyTokenBudget;
    private final long version;
    private final UtcTimestamp createdAt;
    private final PrincipalId createdBy;
    private final UtcTimestamp updatedAt;
    private final PrincipalId updatedBy;

    private OrganizationQuotaConfiguration(
            OrganizationId organizationId,
            Integer monthlyTaskIntakeLimit,
            Long monthlyTokenBudget,
            long version,
            UtcTimestamp createdAt,
            PrincipalId createdBy,
            UtcTimestamp updatedAt,
            PrincipalId updatedBy) {
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId");
        this.monthlyTaskIntakeLimit = requireLimit(monthlyTaskIntakeLimit, "monthlyTaskIntakeLimit");
        this.monthlyTokenBudget = requireLimit(monthlyTokenBudget, "monthlyTokenBudget");
        if (version < 0) {
            throw new DomainValidationException(
                    "organizationQuotaConfiguration.version", "must not be negative");
        }
        this.version = version;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.updatedBy = Objects.requireNonNull(updatedBy, "updatedBy");
    }

    /** Materializes the first quota configuration row; null dimensions stay unconfigured. */
    public static OrganizationQuotaConfiguration initial(
            OrganizationId organizationId,
            Integer monthlyTaskIntakeLimit,
            Long monthlyTokenBudget,
            PrincipalId actor,
            UtcTimestamp occurredAt) {
        return new OrganizationQuotaConfiguration(
                organizationId, monthlyTaskIntakeLimit, monthlyTokenBudget, 0,
                occurredAt, actor, occurredAt, actor);
    }

    /** Restores a persisted quota configuration row. */
    public static OrganizationQuotaConfiguration reconstitute(
            OrganizationId organizationId,
            Integer monthlyTaskIntakeLimit,
            Long monthlyTokenBudget,
            long version,
            UtcTimestamp createdAt,
            PrincipalId createdBy,
            UtcTimestamp updatedAt,
            PrincipalId updatedBy) {
        return new OrganizationQuotaConfiguration(
                organizationId, monthlyTaskIntakeLimit, monthlyTokenBudget, version,
                createdAt, createdBy, updatedAt, updatedBy);
    }

    /**
     * Replaces both dimension limits under optimistic versioning.
     *
     * @return the successor state at version {@code expectedVersion + 1}
     */
    public OrganizationQuotaConfiguration changeLimits(
            long expectedVersion,
            Integer monthlyTaskIntakeLimit,
            Long monthlyTokenBudget,
            PrincipalId actor,
            UtcTimestamp occurredAt) {
        if (expectedVersion != version) {
            throw new OptimisticLockConflictException(
                    "ORGANIZATION_QUOTA_CONFIGURATION",
                    organizationId.value().toString(),
                    expectedVersion,
                    version);
        }
        return new OrganizationQuotaConfiguration(
                organizationId,
                monthlyTaskIntakeLimit,
                monthlyTokenBudget,
                expectedVersion + 1,
                createdAt,
                createdBy,
                occurredAt,
                actor);
    }

    public OrganizationId organizationId() {
        return organizationId;
    }

    /** Empty means the intake dimension is unconfigured (admission skips it). */
    public Optional<Integer> monthlyTaskIntakeLimit() {
        return Optional.ofNullable(monthlyTaskIntakeLimit);
    }

    /** Empty means the token budget dimension is unconfigured (admission skips it). */
    public OptionalLong monthlyTokenBudget() {
        return monthlyTokenBudget == null ? OptionalLong.empty() : OptionalLong.of(monthlyTokenBudget);
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

    private static <N extends Number> N requireLimit(N limit, String field) {
        if (limit == null) {
            return null;
        }
        if (limit.longValue() < MIN_LIMIT) {
            throw new DomainValidationException(
                    "organizationQuotaConfiguration." + field,
                    "must not be lower than " + MIN_LIMIT);
        }
        return limit;
    }
}
