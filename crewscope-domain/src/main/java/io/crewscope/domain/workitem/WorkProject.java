package io.crewscope.domain.workitem;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.workspace.Workspace;
import io.crewscope.domain.workspace.WorkspaceStatus;
import io.crewscope.domain.workspace.WorkspaceType;
import java.util.Objects;

/** Durable project boundary for WorkItems inside one active Team Workspace. */
public final class WorkProject {

    public static final int MAX_NAME_LENGTH = 200;

    private final WorkProjectId id;
    private final WorkProjectScope scope;
    private final WorkProjectKey key;
    private final String name;
    private final WorkProjectStatus status;
    private final long version;
    private final AuditMetadata audit;

    private WorkProject(
            WorkProjectId id,
            WorkProjectScope scope,
            WorkProjectKey key,
            String name,
            WorkProjectStatus status,
            long version,
            AuditMetadata audit) {
        this.id = Objects.requireNonNull(id, "id");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.key = Objects.requireNonNull(key, "key");
        this.name = requireName(name);
        this.status = Objects.requireNonNull(status, "status");
        this.version = requireVersion(version);
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    /** Creates an active project after closing the Team and Workspace scope references. */
    public static WorkProject create(
            WorkProjectId id,
            WorkProjectKey key,
            String name,
            Team team,
            Workspace workspace,
            Principal creator,
            UtcTimestamp occurredAt) {
        Team requiredTeam = Objects.requireNonNull(team, "team");
        Workspace requiredWorkspace = Objects.requireNonNull(workspace, "workspace");
        if (!requiredTeam.isActive()) {
            throw new DomainValidationException(
                    "workProject.teamId", "must reference an active Team");
        }
        if (requiredWorkspace.type() != WorkspaceType.TEAM
                || requiredWorkspace.status() != WorkspaceStatus.ACTIVE
                || !requiredWorkspace.scope().organizationId().equals(requiredTeam.organizationId())
                || requiredWorkspace.scope().teamId().filter(requiredTeam.id()::equals).isEmpty()) {
            throw new DomainValidationException(
                    "workProject.workspaceId",
                    "must reference an active Workspace of the Project Team");
        }
        PrincipalId actorId = WorkItemActorPolicy.requireActiveInScope(
                creator,
                requiredTeam.organizationId(),
                requiredTeam.id(),
                "workProject.createdByPrincipalId");
        return new WorkProject(
                id,
                new WorkProjectScope(
                        requiredTeam.organizationId(),
                        requiredTeam.id(),
                        requiredWorkspace.id()),
                key,
                name,
                WorkProjectStatus.ACTIVE,
                0,
                AuditMetadata.createdBy(actorId, occurredAt));
    }

    /** Reconstitutes a committed project without replaying a lifecycle transition. */
    public static WorkProject reconstitute(
            WorkProjectId id,
            WorkProjectScope scope,
            WorkProjectKey key,
            String name,
            WorkProjectStatus status,
            long version,
            AuditMetadata audit) {
        return new WorkProject(id, scope, key, name, status, version, audit);
    }

    public WorkProject rename(String targetName, Principal actor, UtcTimestamp occurredAt) {
        requireMutable(actor);
        PrincipalId actorId = WorkItemActorPolicy.requireActiveInScope(
                actor,
                scope.organizationId(),
                scope.teamId(),
                "workProject.updatedByPrincipalId");
        return new WorkProject(
                id,
                scope,
                key,
                targetName,
                status,
                version + 1,
                audit.modifiedBy(actorId, occurredAt));
    }

    public WorkProject archive(Principal actor, UtcTimestamp occurredAt) {
        requireMutable(actor);
        PrincipalId actorId = WorkItemActorPolicy.requireActiveInScope(
                actor,
                scope.organizationId(),
                scope.teamId(),
                "workProject.updatedByPrincipalId");
        return new WorkProject(
                id,
                scope,
                key,
                name,
                WorkProjectStatus.ARCHIVED,
                version + 1,
                audit.modifiedBy(actorId, occurredAt));
    }

    public boolean acceptsWork() {
        return status == WorkProjectStatus.ACTIVE;
    }

    public WorkProjectId id() {
        return id;
    }

    public WorkProjectScope scope() {
        return scope;
    }

    public WorkProjectKey key() {
        return key;
    }

    public String name() {
        return name;
    }

    public WorkProjectStatus status() {
        return status;
    }

    public long version() {
        return version;
    }

    public AuditMetadata audit() {
        return audit;
    }

    private void requireMutable(Principal actor) {
        Objects.requireNonNull(actor, "actor");
        if (status == WorkProjectStatus.ARCHIVED) {
            throw new InvalidStateTransitionException(
                    "WorkProject", id, WorkProjectStatus.ARCHIVED, WorkProjectStatus.ARCHIVED);
        }
    }

    private static String requireName(String value) {
        if (value == null || value.isBlank()) {
            throw new DomainValidationException("workProject.name", "must not be blank");
        }
        String normalized = value.strip();
        if (normalized.length() > MAX_NAME_LENGTH) {
            throw new DomainValidationException(
                    "workProject.name",
                    "must contain at most " + MAX_NAME_LENGTH + " characters");
        }
        return normalized;
    }

    private static long requireVersion(long value) {
        if (value < 0) {
            throw new DomainValidationException("workProject.version", "must not be negative");
        }
        return value;
    }
}
