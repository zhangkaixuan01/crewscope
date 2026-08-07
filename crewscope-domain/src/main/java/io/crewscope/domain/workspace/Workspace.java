package io.crewscope.domain.workspace;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.Team;
import java.util.Objects;
import java.util.Optional;

/** Product Workspace shared by a Team or owned by one Personal Agent user. */
public final class Workspace {

    public static final int MAX_NAME_LENGTH = 200;

    private final WorkspaceId id;
    private final WorkspaceScope scope;
    private final WorkspaceType type;
    private final Optional<PrincipalId> ownerPrincipalId;
    private final String name;
    private final WorkspaceStatus status;
    private final long version;
    private final AuditMetadata audit;

    private Workspace(
            WorkspaceId id,
            WorkspaceScope scope,
            WorkspaceType type,
            Optional<PrincipalId> ownerPrincipalId,
            String name,
            WorkspaceStatus status,
            long version,
            AuditMetadata audit) {
        this.id = Objects.requireNonNull(id, "id");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.type = Objects.requireNonNull(type, "type");
        this.scope.validateFor(type);
        this.ownerPrincipalId = Objects.requireNonNull(ownerPrincipalId, "ownerPrincipalId");
        this.name = requireName(name);
        this.status = Objects.requireNonNull(status, "status");
        this.version = requireVersion(version);
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    /** Creates the shared default Workspace for an active Team. */
    public static Workspace createTeam(
            WorkspaceId id,
            Team team,
            Principal ownerPrincipal,
            String name,
            UtcTimestamp occurredAt) {
        Team requiredTeam = Objects.requireNonNull(team, "team");
        if (!requiredTeam.isActive()) {
            throw new DomainValidationException(
                    "workspace.teamId", "must reference an active Team");
        }
        PrincipalId requiredOwner =
                requireActiveUser(ownerPrincipal, requiredTeam.organizationId());
        return new Workspace(
                id,
                WorkspaceScope.team(requiredTeam.organizationId(), requiredTeam.id()),
                WorkspaceType.TEAM,
                Optional.of(requiredOwner),
                name,
                WorkspaceStatus.ACTIVE,
                0,
                AuditMetadata.createdBy(requiredOwner, occurredAt));
    }

    /** Creates a Personal Workspace owned and audited by its USER Principal. */
    public static Workspace createPersonal(
            WorkspaceId id,
            Principal ownerPrincipal,
            String name,
            UtcTimestamp occurredAt) {
        Principal requiredPrincipal = Objects.requireNonNull(ownerPrincipal, "ownerPrincipal");
        PrincipalId requiredOwner =
                requireActiveUser(requiredPrincipal, requiredPrincipal.scope().organizationId());
        return new Workspace(
                id,
                WorkspaceScope.personal(requiredPrincipal.scope().organizationId()),
                WorkspaceType.PERSONAL,
                Optional.of(requiredOwner),
                name,
                WorkspaceStatus.ACTIVE,
                0,
                AuditMetadata.createdBy(requiredOwner, occurredAt));
    }

    /** Reconstitutes legacy Workspaces whose optional owner may predate the Principal table. */
    public static Workspace reconstitute(
            WorkspaceId id,
            WorkspaceScope scope,
            WorkspaceType type,
            Optional<PrincipalId> ownerPrincipalId,
            String name,
            WorkspaceStatus status,
            long version,
            AuditMetadata audit) {
        return new Workspace(
                id, scope, type, ownerPrincipalId, name, status, version, audit);
    }

    public Workspace archive(PrincipalId actor, UtcTimestamp occurredAt) {
        if (status == WorkspaceStatus.ARCHIVED) {
            throw new InvalidStateTransitionException(
                    "Workspace", id, WorkspaceStatus.ARCHIVED, WorkspaceStatus.ARCHIVED);
        }
        return new Workspace(
                id,
                scope,
                type,
                ownerPrincipalId,
                name,
                WorkspaceStatus.ARCHIVED,
                version + 1,
                audit.modifiedBy(actor, occurredAt));
    }

    public WorkspaceId id() {
        return id;
    }

    public WorkspaceScope scope() {
        return scope;
    }

    public WorkspaceType type() {
        return type;
    }

    public Optional<PrincipalId> ownerPrincipalId() {
        return ownerPrincipalId;
    }

    public String name() {
        return name;
    }

    public WorkspaceStatus status() {
        return status;
    }

    public long version() {
        return version;
    }

    public AuditMetadata audit() {
        return audit;
    }

    private static String requireName(String value) {
        if (value == null || value.isBlank()) {
            throw new DomainValidationException("workspace.name", "must not be blank");
        }
        String normalized = value.strip();
        if (normalized.length() > MAX_NAME_LENGTH) {
            throw new DomainValidationException(
                    "workspace.name",
                    "must contain at most " + MAX_NAME_LENGTH + " characters");
        }
        return normalized;
    }

    private static long requireVersion(long value) {
        if (value < 0) {
            throw new DomainValidationException("workspace.version", "must not be negative");
        }
        return value;
    }

    private static PrincipalId requireActiveUser(
            Principal principal, OrganizationId organizationId) {
        Principal requiredPrincipal = Objects.requireNonNull(principal, "ownerPrincipal");
        if (requiredPrincipal.type() != PrincipalType.USER || !requiredPrincipal.canAct()) {
            throw new DomainValidationException(
                    "workspace.ownerPrincipalId", "must reference an active USER Principal");
        }
        if (!requiredPrincipal.scope().organizationId().equals(organizationId)) {
            throw new DomainValidationException(
                    "workspace.ownerPrincipalId", "must belong to the Workspace Organization");
        }
        return requiredPrincipal.id();
    }
}
