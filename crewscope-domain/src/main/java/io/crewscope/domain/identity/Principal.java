package io.crewscope.domain.identity;

import io.crewscope.domain.shared.audit.LifecycleMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Unified behavior subject for users, Agents and platform services. */
public final class Principal {

    public static final int MAX_DISPLAY_NAME_LENGTH = 200;

    private static final Map<PrincipalStatus, Set<PrincipalStatus>> ALLOWED_TRANSITIONS = Map.of(
            PrincipalStatus.ACTIVE,
            EnumSet.of(
                    PrincipalStatus.SUSPENDED,
                    PrincipalStatus.DISABLED,
                    PrincipalStatus.ARCHIVED),
            PrincipalStatus.SUSPENDED,
            EnumSet.of(
                    PrincipalStatus.ACTIVE,
                    PrincipalStatus.DISABLED,
                    PrincipalStatus.ARCHIVED),
            PrincipalStatus.DISABLED,
            EnumSet.of(PrincipalStatus.ACTIVE, PrincipalStatus.ARCHIVED),
            PrincipalStatus.ARCHIVED,
            EnumSet.noneOf(PrincipalStatus.class));

    private final PrincipalId id;
    private final PrincipalScope scope;
    private final PrincipalType type;
    private final Optional<PrincipalId> ownerPrincipalId;
    private final String displayName;
    private final Optional<ExternalIdentity> externalIdentity;
    private final PrincipalVisibility visibility;
    private final PrincipalStatus status;
    private final long version;
    private final LifecycleMetadata lifecycle;

    private Principal(
            PrincipalId id,
            PrincipalScope scope,
            PrincipalType type,
            Optional<PrincipalId> ownerPrincipalId,
            String displayName,
            Optional<ExternalIdentity> externalIdentity,
            PrincipalVisibility visibility,
            PrincipalStatus status,
            long version,
            LifecycleMetadata lifecycle) {
        this.id = Objects.requireNonNull(id, "id");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.type = Objects.requireNonNull(type, "type");
        this.status = Objects.requireNonNull(status, "status");
        this.ownerPrincipalId = requireOwner(type, ownerPrincipalId, id, this.status);
        this.displayName = requireDisplayName(displayName);
        this.externalIdentity = Objects.requireNonNull(externalIdentity, "externalIdentity");
        this.visibility = requireVisibility(scope, visibility);
        this.version = requireVersion(version);
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    }

    /** Creates an active Principal; Agent ownership is mandatory and immutable. */
    public static Principal create(
            PrincipalId id,
            PrincipalScope scope,
            PrincipalType type,
            Optional<PrincipalId> ownerPrincipalId,
            String displayName,
            Optional<ExternalIdentity> externalIdentity,
            PrincipalVisibility visibility,
            UtcTimestamp occurredAt) {
        return new Principal(
                id,
                scope,
                type,
                ownerPrincipalId,
                displayName,
                externalIdentity,
                visibility,
                PrincipalStatus.ACTIVE,
                0,
                LifecycleMetadata.createdAt(occurredAt));
    }

    /** Reconstitutes a persisted Principal without applying lifecycle transitions. */
    public static Principal reconstitute(
            PrincipalId id,
            PrincipalScope scope,
            PrincipalType type,
            Optional<PrincipalId> ownerPrincipalId,
            String displayName,
            Optional<ExternalIdentity> externalIdentity,
            PrincipalVisibility visibility,
            PrincipalStatus status,
            long version,
            LifecycleMetadata lifecycle) {
        return new Principal(
                id,
                scope,
                type,
                ownerPrincipalId,
                displayName,
                externalIdentity,
                visibility,
                status,
                version,
                lifecycle);
    }

    /** Changes access state while preserving the Principal's stable type and ownership. */
    public Principal transitionTo(PrincipalStatus target, UtcTimestamp occurredAt) {
        Objects.requireNonNull(target, "target");
        if (!ALLOWED_TRANSITIONS.get(status).contains(target)) {
            throw new InvalidStateTransitionException("Principal", id, status, target);
        }
        return new Principal(
                id,
                scope,
                type,
                ownerPrincipalId,
                displayName,
                externalIdentity,
                visibility,
                target,
                version + 1,
                lifecycle.modifiedAt(occurredAt));
    }

    /**
     * Completes ownership for a disabled imported Agent whose historical source did not contain an
     * owner. Ownership is immutable after it has been assigned.
     */
    public Principal assignOwner(PrincipalId ownerPrincipalId, UtcTimestamp occurredAt) {
        if (!type.isAgent()) {
            throw new DomainValidationException(
                    "principal.ownerPrincipalId", "is only allowed for an Agent Principal");
        }
        if (this.ownerPrincipalId.isPresent()) {
            throw new DomainValidationException(
                    "principal.ownerPrincipalId", "has already been assigned");
        }
        if (status != PrincipalStatus.DISABLED) {
            throw new DomainValidationException(
                    "principal.ownerPrincipalId",
                    "can only complete ownership for a disabled imported Principal");
        }
        return new Principal(
                id,
                scope,
                type,
                Optional.of(Objects.requireNonNull(ownerPrincipalId, "ownerPrincipalId")),
                displayName,
                externalIdentity,
                visibility,
                status,
                version + 1,
                lifecycle.modifiedAt(occurredAt));
    }

    public boolean canAct() {
        return status == PrincipalStatus.ACTIVE;
    }

    public PrincipalId id() {
        return id;
    }

    public PrincipalScope scope() {
        return scope;
    }

    public PrincipalType type() {
        return type;
    }

    public Optional<PrincipalId> ownerPrincipalId() {
        return ownerPrincipalId;
    }

    public String displayName() {
        return displayName;
    }

    public Optional<ExternalIdentity> externalIdentity() {
        return externalIdentity;
    }

    public PrincipalVisibility visibility() {
        return visibility;
    }

    public PrincipalStatus status() {
        return status;
    }

    public long version() {
        return version;
    }

    public LifecycleMetadata lifecycle() {
        return lifecycle;
    }

    private static Optional<PrincipalId> requireOwner(
            PrincipalType type,
            Optional<PrincipalId> owner,
            PrincipalId id,
            PrincipalStatus status) {
        Optional<PrincipalId> requiredOwner = Objects.requireNonNull(owner, "ownerPrincipalId");
        if (type.isAgent() && requiredOwner.isEmpty() && status == PrincipalStatus.ACTIVE) {
            throw new DomainValidationException(
                    "principal.ownerPrincipalId", "is required for an Agent Principal");
        }
        if (!type.isAgent() && requiredOwner.isPresent()) {
            throw new DomainValidationException(
                    "principal.ownerPrincipalId", "is only allowed for an Agent Principal");
        }
        if (requiredOwner.filter(id::equals).isPresent()) {
            throw new DomainValidationException(
                    "principal.ownerPrincipalId", "must not reference the Principal itself");
        }
        return requiredOwner;
    }

    private static PrincipalVisibility requireVisibility(
            PrincipalScope scope, PrincipalVisibility visibility) {
        PrincipalVisibility requiredVisibility = Objects.requireNonNull(visibility, "visibility");
        if (requiredVisibility == PrincipalVisibility.TEAM && scope.teamId().isEmpty()) {
            throw new DomainValidationException(
                    "principal.visibility", "TEAM visibility requires a Team scope");
        }
        return requiredVisibility;
    }

    private static String requireDisplayName(String value) {
        if (value == null || value.isBlank()) {
            throw new DomainValidationException("principal.displayName", "must not be blank");
        }
        String normalized = value.strip();
        if (normalized.length() > MAX_DISPLAY_NAME_LENGTH) {
            throw new DomainValidationException(
                    "principal.displayName",
                    "must contain at most " + MAX_DISPLAY_NAME_LENGTH + " characters");
        }
        return normalized;
    }

    private static long requireVersion(long value) {
        if (value < 0) {
            throw new DomainValidationException("principal.version", "must not be negative");
        }
        return value;
    }
}
