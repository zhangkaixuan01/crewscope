package io.crewscope.domain.team;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Objects;
import java.util.Optional;

/** Concrete Team or WorkProject target attached to a MemberRole grant. */
public record RoleScope(RoleScopeType type, Optional<WorkProjectId> workProjectId) {

    public RoleScope {
        type = Objects.requireNonNull(type, "type");
        workProjectId = Objects.requireNonNull(workProjectId, "workProjectId");
        if (type == RoleScopeType.TEAM && workProjectId.isPresent()) {
            throw new DomainValidationException(
                    "memberRole.scope", "TEAM scope must not contain a WorkProject");
        }
        if (type == RoleScopeType.WORK_PROJECT && workProjectId.isEmpty()) {
            throw new DomainValidationException(
                    "memberRole.scope", "WORK_PROJECT scope requires a WorkProject");
        }
    }

    public static RoleScope team() {
        return new RoleScope(RoleScopeType.TEAM, Optional.empty());
    }

    public static RoleScope workProject(WorkProjectId workProjectId) {
        return new RoleScope(
                RoleScopeType.WORK_PROJECT,
                Optional.of(Objects.requireNonNull(workProjectId, "workProjectId")));
    }
}
