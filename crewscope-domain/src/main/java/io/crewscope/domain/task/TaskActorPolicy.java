package io.crewscope.domain.task;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.workitem.WorkItemScope;
import java.util.Objects;

/** Shared Task scope guard; application services remain responsible for role authorization. */
final class TaskActorPolicy {

    private TaskActorPolicy() {}

    static PrincipalId requireActiveInScope(
            Principal actor, WorkItemScope scope, String field) {
        Principal required = Objects.requireNonNull(actor, "actor");
        WorkItemScope requiredScope = Objects.requireNonNull(scope, "scope");
        boolean outsideTeam = required.scope().teamId().isPresent()
                && required.scope().teamId().filter(requiredScope.teamId()::equals).isEmpty();
        if (!required.canAct()
                || !required.scope().organizationId().equals(requiredScope.organizationId())
                || outsideTeam) {
            throw new DomainValidationException(
                    field, "must reference an active Principal in the Task scope");
        }
        return required.id();
    }
}
