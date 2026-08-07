package io.crewscope.domain.team;

import io.crewscope.domain.shared.audit.LifecycleMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Stable Team-level permission definition, separate from work-object responsibility. */
public final class TeamRole {

    public static final int MAX_NAME_LENGTH = 200;

    private static final Map<TeamRoleStatus, Set<TeamRoleStatus>> ALLOWED_TRANSITIONS = Map.of(
            TeamRoleStatus.ACTIVE,
            EnumSet.of(TeamRoleStatus.DISABLED, TeamRoleStatus.ARCHIVED),
            TeamRoleStatus.DISABLED,
            EnumSet.of(TeamRoleStatus.ACTIVE, TeamRoleStatus.ARCHIVED),
            TeamRoleStatus.ARCHIVED,
            EnumSet.noneOf(TeamRoleStatus.class));

    private final TeamRoleId id;
    private final TeamScope scope;
    private final TeamRoleKey key;
    private final String name;
    private final Optional<String> description;
    private final boolean builtIn;
    private final Set<TeamPermission> permissions;
    private final RoleScopeType scopeType;
    private final TeamRoleStatus status;
    private final long version;
    private final LifecycleMetadata lifecycle;

    private TeamRole(
            TeamRoleId id,
            TeamScope scope,
            TeamRoleKey key,
            String name,
            Optional<String> description,
            boolean builtIn,
            Set<TeamPermission> permissions,
            RoleScopeType scopeType,
            TeamRoleStatus status,
            long version,
            LifecycleMetadata lifecycle) {
        this.id = Objects.requireNonNull(id, "id");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.key = Objects.requireNonNull(key, "key");
        this.name = requireName(name);
        this.description = normalizeDescription(description);
        this.builtIn = builtIn;
        this.permissions = Set.copyOf(Objects.requireNonNull(permissions, "permissions"));
        this.scopeType = Objects.requireNonNull(scopeType, "scopeType");
        validateDefinition(key, builtIn, this.permissions, scopeType);
        this.status = Objects.requireNonNull(status, "status");
        this.version = requireVersion(version);
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    }

    /** Creates one of the product-owned Team-wide roles. */
    public static TeamRole createBuiltIn(
            TeamRoleId id,
            TeamScope scope,
            BuiltInTeamRole definition,
            UtcTimestamp occurredAt) {
        BuiltInTeamRole requiredDefinition = Objects.requireNonNull(definition, "definition");
        return new TeamRole(
                id,
                scope,
                requiredDefinition.key(),
                requiredDefinition.displayName(),
                Optional.empty(),
                true,
                requiredDefinition.permissions(),
                RoleScopeType.TEAM,
                TeamRoleStatus.ACTIVE,
                0,
                LifecycleMetadata.createdAt(occurredAt));
    }

    /** Creates a custom role that can be granted Team-wide or per WorkProject. */
    public static TeamRole createCustom(
            TeamRoleId id,
            TeamScope scope,
            TeamRoleKey key,
            String name,
            Optional<String> description,
            Set<TeamPermission> permissions,
            RoleScopeType scopeType,
            UtcTimestamp occurredAt) {
        return new TeamRole(
                id,
                scope,
                key,
                name,
                description,
                false,
                permissions,
                scopeType,
                TeamRoleStatus.ACTIVE,
                0,
                LifecycleMetadata.createdAt(occurredAt));
    }

    /** Reconstitutes a persisted role definition without changing its version. */
    public static TeamRole reconstitute(
            TeamRoleId id,
            TeamScope scope,
            TeamRoleKey key,
            String name,
            Optional<String> description,
            boolean builtIn,
            Set<TeamPermission> permissions,
            RoleScopeType scopeType,
            TeamRoleStatus status,
            long version,
            LifecycleMetadata lifecycle) {
        return new TeamRole(
                id,
                scope,
                key,
                name,
                description,
                builtIn,
                permissions,
                scopeType,
                status,
                version,
                lifecycle);
    }

    public TeamRole transitionTo(TeamRoleStatus target, UtcTimestamp occurredAt) {
        Objects.requireNonNull(target, "target");
        if (!ALLOWED_TRANSITIONS.get(status).contains(target)) {
            throw new InvalidStateTransitionException("TeamRole", id, status, target);
        }
        return new TeamRole(
                id,
                scope,
                key,
                name,
                description,
                builtIn,
                permissions,
                scopeType,
                target,
                version + 1,
                lifecycle.modifiedAt(occurredAt));
    }

    public boolean isGrantable() {
        return status == TeamRoleStatus.ACTIVE;
    }

    public boolean isBuiltIn(BuiltInTeamRole definition) {
        return builtIn
                && key.equals(Objects.requireNonNull(definition, "definition").key());
    }

    public TeamRoleId id() {
        return id;
    }

    public TeamScope scope() {
        return scope;
    }

    public TeamRoleKey key() {
        return key;
    }

    public String name() {
        return name;
    }

    public Optional<String> description() {
        return description;
    }

    public boolean builtIn() {
        return builtIn;
    }

    public Set<TeamPermission> permissions() {
        return permissions;
    }

    public RoleScopeType scopeType() {
        return scopeType;
    }

    public TeamRoleStatus status() {
        return status;
    }

    public long version() {
        return version;
    }

    public LifecycleMetadata lifecycle() {
        return lifecycle;
    }

    private static String requireName(String value) {
        if (value == null || value.isBlank()) {
            throw new DomainValidationException("teamRole.name", "must not be blank");
        }
        String normalized = value.strip();
        if (normalized.length() > MAX_NAME_LENGTH) {
            throw new DomainValidationException(
                    "teamRole.name", "must contain at most " + MAX_NAME_LENGTH + " characters");
        }
        return normalized;
    }

    private static Optional<String> normalizeDescription(Optional<String> value) {
        Optional<String> required = Objects.requireNonNull(value, "description");
        if (required.isPresent() && required.orElseThrow().isBlank()) {
            return Optional.empty();
        }
        return required.map(String::strip);
    }

    private static void validateDefinition(
            TeamRoleKey key,
            boolean builtIn,
            Set<TeamPermission> permissions,
            RoleScopeType scopeType) {
        Optional<BuiltInTeamRole> definition = BuiltInTeamRole.fromKey(key);
        if (!builtIn && definition.isPresent()) {
            throw new DomainValidationException(
                    "teamRole.key", "is reserved for a built-in TeamRole");
        }
        if (builtIn && definition.isEmpty()) {
            throw new DomainValidationException(
                    "teamRole.key", "must identify a supported built-in TeamRole");
        }
        if (builtIn
                && (scopeType != RoleScopeType.TEAM
                        || !permissions.equals(definition.orElseThrow().permissions()))) {
            throw new DomainValidationException(
                    "teamRole.builtIn", "must preserve the product-owned scope and permissions");
        }
    }

    private static long requireVersion(long value) {
        if (value < 0) {
            throw new DomainValidationException("teamRole.version", "must not be negative");
        }
        return value;
    }
}
