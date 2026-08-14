package io.crewscope.domain.runtime;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;

/** Organization and environment scoped registry entry for one runtime implementation. */
public final class ExecutionRuntime {

    private static final String KEY_PATTERN = "[a-z][a-z0-9-]{2,63}";
    private static final String VERSION_PATTERN =
            "[0-9]+(?:\\.[0-9]+){1,3}(?:[-+][A-Za-z0-9.-]+)?";

    private final ExecutionRuntimeId id;
    private final OrganizationId organizationId;
    private final RuntimeEnvironment environment;
    private final String key;
    private final String displayName;
    private final String implementationVersion;
    private final RuntimeCapabilities capabilities;
    private final ExecutionRuntimeStatus status;
    private final long version;
    private final AuditMetadata audit;

    private ExecutionRuntime(
            ExecutionRuntimeId id,
            OrganizationId organizationId,
            RuntimeEnvironment environment,
            String key,
            String displayName,
            String implementationVersion,
            RuntimeCapabilities capabilities,
            ExecutionRuntimeStatus status,
            long version,
            AuditMetadata audit) {
        this.id = Objects.requireNonNull(id, "id");
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.key = requireKey(key);
        this.displayName = requireText(displayName, "executionRuntime.displayName", 120);
        this.implementationVersion = requireImplementationVersion(implementationVersion);
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.status = Objects.requireNonNull(status, "status");
        if (version < 0) {
            throw new DomainValidationException("executionRuntime.version", "must not be negative");
        }
        this.version = version;
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    /** Registers an active runtime under a stable Organization and environment key. */
    public static ExecutionRuntime register(
            ExecutionRuntimeId id,
            OrganizationId organizationId,
            RuntimeEnvironment environment,
            String key,
            String displayName,
            String implementationVersion,
            RuntimeCapabilities capabilities,
            Principal actor,
            UtcTimestamp occurredAt) {
        PrincipalId actorId = RuntimeActorPolicy.requireActiveInOrganization(
                actor, organizationId, "executionRuntime.createdByPrincipalId");
        return new ExecutionRuntime(
                id, organizationId, environment, key, displayName, implementationVersion,
                capabilities, ExecutionRuntimeStatus.ACTIVE, 0,
                AuditMetadata.createdBy(actorId, occurredAt));
    }

    public static ExecutionRuntime reconstitute(
            ExecutionRuntimeId id,
            OrganizationId organizationId,
            RuntimeEnvironment environment,
            String key,
            String displayName,
            String implementationVersion,
            RuntimeCapabilities capabilities,
            ExecutionRuntimeStatus status,
            long version,
            AuditMetadata audit) {
        return new ExecutionRuntime(
                id, organizationId, environment, key, displayName, implementationVersion,
                capabilities, status, version, audit);
    }

    /** Publishes an exact capability snapshot after the corresponding adapter boundary is wired. */
    public ExecutionRuntime publishCapabilities(
            RuntimeCapabilities replacement,
            String replacementImplementationVersion,
            long expectedVersion,
            Principal actor,
            UtcTimestamp occurredAt) {
        requireMutable(expectedVersion);
        RuntimeCapabilities next = Objects.requireNonNull(replacement, "replacement");
        String nextVersion = requireImplementationVersion(replacementImplementationVersion);
        if (capabilities.equals(next) && implementationVersion.equals(nextVersion)) {
            throw new DomainValidationException(
                    "executionRuntime.capabilities", "must change capabilities or implementation version");
        }
        PrincipalId actorId = RuntimeActorPolicy.requireActiveInOrganization(
                actor, organizationId, "executionRuntime.updatedByPrincipalId");
        return copy(nextVersion, next, status, version + 1,
                audit.modifiedBy(actorId, occurredAt));
    }

    public ExecutionRuntime disable(
            long expectedVersion, Principal actor, UtcTimestamp occurredAt) {
        return transition(
                ExecutionRuntimeStatus.ACTIVE, ExecutionRuntimeStatus.DISABLED,
                expectedVersion, actor, occurredAt);
    }

    public ExecutionRuntime activate(
            long expectedVersion, Principal actor, UtcTimestamp occurredAt) {
        return transition(
                ExecutionRuntimeStatus.DISABLED, ExecutionRuntimeStatus.ACTIVE,
                expectedVersion, actor, occurredAt);
    }

    public ExecutionRuntime archive(
            long expectedVersion, Principal actor, UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        if (status == ExecutionRuntimeStatus.ARCHIVED) {
            throw new InvalidStateTransitionException(
                    "ExecutionRuntime", id, status, ExecutionRuntimeStatus.ARCHIVED);
        }
        PrincipalId actorId = RuntimeActorPolicy.requireActiveInOrganization(
                actor, organizationId, "executionRuntime.updatedByPrincipalId");
        return copy(implementationVersion, capabilities, ExecutionRuntimeStatus.ARCHIVED,
                version + 1, audit.modifiedBy(actorId, occurredAt));
    }

    public boolean supports(RuntimeCapabilities required) {
        return status == ExecutionRuntimeStatus.ACTIVE && capabilities.supports(required);
    }

    private ExecutionRuntime transition(
            ExecutionRuntimeStatus current,
            ExecutionRuntimeStatus target,
            long expectedVersion,
            Principal actor,
            UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        if (status != current) {
            throw new InvalidStateTransitionException("ExecutionRuntime", id, status, target);
        }
        PrincipalId actorId = RuntimeActorPolicy.requireActiveInOrganization(
                actor, organizationId, "executionRuntime.updatedByPrincipalId");
        return copy(implementationVersion, capabilities, target, version + 1,
                audit.modifiedBy(actorId, occurredAt));
    }

    private void requireMutable(long expectedVersion) {
        requireExpectedVersion(expectedVersion);
        if (status == ExecutionRuntimeStatus.ARCHIVED) {
            throw new InvalidStateTransitionException(
                    "ExecutionRuntime", id, status, status);
        }
    }

    private void requireExpectedVersion(long expectedVersion) {
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
        if (version != expectedVersion) {
            throw new OptimisticLockConflictException(
                    "ExecutionRuntime", id, expectedVersion, version);
        }
    }

    private ExecutionRuntime copy(
            String targetImplementationVersion,
            RuntimeCapabilities targetCapabilities,
            ExecutionRuntimeStatus targetStatus,
            long targetVersion,
            AuditMetadata targetAudit) {
        return new ExecutionRuntime(
                id, organizationId, environment, key, displayName, targetImplementationVersion,
                targetCapabilities, targetStatus, targetVersion, targetAudit);
    }

    static String requireKey(String value) {
        String required = requireText(value, "executionRuntime.key", 64);
        if (!required.matches(KEY_PATTERN)) {
            throw new DomainValidationException(
                    "executionRuntime.key", "must use a stable lowercase kebab-case key");
        }
        return required;
    }

    private static String requireImplementationVersion(String value) {
        String required = requireText(value, "executionRuntime.implementationVersion", 64);
        if (!required.matches(VERSION_PATTERN)) {
            throw new DomainValidationException(
                    "executionRuntime.implementationVersion", "must use a semantic numeric version");
        }
        return required;
    }

    static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new DomainValidationException(field, "must not be blank");
        }
        String required = value.strip();
        if (required.length() > maxLength || required.chars().anyMatch(Character::isISOControl)) {
            throw new DomainValidationException(field, "contains unsupported characters or length");
        }
        return required;
    }

    public ExecutionRuntimeId id() { return id; }
    public OrganizationId organizationId() { return organizationId; }
    public RuntimeEnvironment environment() { return environment; }
    public String key() { return key; }
    public String displayName() { return displayName; }
    public String implementationVersion() { return implementationVersion; }
    public RuntimeCapabilities capabilities() { return capabilities; }
    public ExecutionRuntimeStatus status() { return status; }
    public long version() { return version; }
    public AuditMetadata audit() { return audit; }
}
