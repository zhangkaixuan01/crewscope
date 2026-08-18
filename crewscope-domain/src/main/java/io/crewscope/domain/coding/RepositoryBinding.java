package io.crewscope.domain.coding;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkProject;
import java.util.Objects;

/** Versioned WorkProject binding to one path-independent managed source repository. */
public final class RepositoryBinding {

    private final RepositoryBindingId id;
    private final RepositoryBindingScope scope;
    private final RepositoryKind kind;
    private final RepositoryKey repositoryKey;
    private final RepositoryBranchName defaultBranch;
    private final RepositoryBindingStatus status;
    private final long version;
    private final AuditMetadata audit;

    private RepositoryBinding(
            RepositoryBindingId id,
            RepositoryBindingScope scope,
            RepositoryKind kind,
            RepositoryKey repositoryKey,
            RepositoryBranchName defaultBranch,
            RepositoryBindingStatus status,
            long version,
            AuditMetadata audit) {
        this.id = Objects.requireNonNull(id, "id");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.repositoryKey = Objects.requireNonNull(repositoryKey, "repositoryKey");
        this.defaultBranch = Objects.requireNonNull(defaultBranch, "defaultBranch");
        this.status = Objects.requireNonNull(status, "status");
        this.version = requireVersion(version, "repositoryBinding.version");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    /** Registers one active local managed repository without accepting a host filesystem path. */
    public static RepositoryBinding registerLocalManaged(
            RepositoryBindingId id,
            WorkProject project,
            RepositoryKey repositoryKey,
            RepositoryBranchName defaultBranch,
            Principal actor,
            UtcTimestamp occurredAt) {
        WorkProject requiredProject = Objects.requireNonNull(project, "project");
        if (!requiredProject.acceptsWork()) {
            throw new DomainValidationException(
                    "repositoryBinding.workProjectId", "must reference an active WorkProject");
        }
        RepositoryBindingScope scope = RepositoryBindingScope.from(requiredProject);
        PrincipalId actorId = RepositoryBindingActorPolicy.requireActiveInScope(
                actor, scope, "repositoryBinding.createdByPrincipalId");
        UtcTimestamp requiredTime = Objects.requireNonNull(occurredAt, "occurredAt");
        return new RepositoryBinding(
                id,
                scope,
                RepositoryKind.LOCAL_MANAGED,
                repositoryKey,
                defaultBranch,
                RepositoryBindingStatus.ACTIVE,
                0,
                AuditMetadata.createdBy(actorId, requiredTime));
    }

    /** Reconstitutes committed binding facts without resolving or exposing a host path. */
    public static RepositoryBinding reconstitute(
            RepositoryBindingId id,
            RepositoryBindingScope scope,
            RepositoryKind kind,
            RepositoryKey repositoryKey,
            RepositoryBranchName defaultBranch,
            RepositoryBindingStatus status,
            long version,
            AuditMetadata audit) {
        return new RepositoryBinding(
                id, scope, kind, repositoryKey, defaultBranch, status, version, audit);
    }

    /** Disables selection by future CodingTarget snapshots. Existing snapshots remain immutable. */
    public RepositoryBinding disable(
            long expectedVersion, Principal actor, UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        if (status != RepositoryBindingStatus.ACTIVE) {
            throw new InvalidStateTransitionException(
                    "RepositoryBinding", id, status, RepositoryBindingStatus.DISABLED);
        }
        return transition(RepositoryBindingStatus.DISABLED, actor, occurredAt);
    }

    /** Re-enables a disabled binding after application-layer repository preflight succeeds. */
    public RepositoryBinding activate(
            long expectedVersion, Principal actor, UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        if (status != RepositoryBindingStatus.DISABLED) {
            throw new InvalidStateTransitionException(
                    "RepositoryBinding", id, status, RepositoryBindingStatus.ACTIVE);
        }
        return transition(RepositoryBindingStatus.ACTIVE, actor, occurredAt);
    }

    /** Changes the default for future snapshots while preserving the stable repository key. */
    public RepositoryBinding changeDefaultBranch(
            RepositoryBranchName target,
            long expectedVersion,
            Principal actor,
            UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        RepositoryBranchName requiredTarget = Objects.requireNonNull(target, "target");
        if (defaultBranch.equals(requiredTarget)) {
            throw new DomainValidationException(
                    "repositoryBinding.defaultBranch", "must differ from the current default branch");
        }
        PrincipalId actorId = RepositoryBindingActorPolicy.requireActiveInScope(
                actor, scope, "repositoryBinding.updatedByPrincipalId");
        return new RepositoryBinding(
                id,
                scope,
                kind,
                repositoryKey,
                requiredTarget,
                status,
                version + 1,
                audit.modifiedBy(actorId, occurredAt));
    }

    public boolean acceptsNewTargets() {
        return status == RepositoryBindingStatus.ACTIVE;
    }

    public RepositoryBindingId id() {
        return id;
    }

    public RepositoryBindingScope scope() {
        return scope;
    }

    public RepositoryKind kind() {
        return kind;
    }

    public RepositoryKey repositoryKey() {
        return repositoryKey;
    }

    public RepositoryBranchName defaultBranch() {
        return defaultBranch;
    }

    public RepositoryBindingStatus status() {
        return status;
    }

    public long version() {
        return version;
    }

    public AuditMetadata audit() {
        return audit;
    }

    private RepositoryBinding transition(
            RepositoryBindingStatus target, Principal actor, UtcTimestamp occurredAt) {
        PrincipalId actorId = RepositoryBindingActorPolicy.requireActiveInScope(
                actor, scope, "repositoryBinding.updatedByPrincipalId");
        return new RepositoryBinding(
                id,
                scope,
                kind,
                repositoryKey,
                defaultBranch,
                target,
                version + 1,
                audit.modifiedBy(actorId, occurredAt));
    }

    private void requireExpectedVersion(long expectedVersion) {
        long requiredExpected = requireVersion(expectedVersion, "expectedVersion");
        if (version != requiredExpected) {
            throw new OptimisticLockConflictException(
                    "RepositoryBinding", id, requiredExpected, version);
        }
    }

    private static long requireVersion(long value, String field) {
        if (value < 0) {
            throw new DomainValidationException(field, "must not be negative");
        }
        return value;
    }
}
