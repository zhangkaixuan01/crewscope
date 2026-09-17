package io.crewscope.application.task;

import io.crewscope.domain.responsibility.ResponsibilityAssignment;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.PrincipalId;
import java.util.List;
import java.util.Objects;

/**
 * The one adjudication of who may control a Task execution attempt.
 *
 * <p>The command side ({@code MemberTaskCommandService}) and the availability projection both consult
 * this class rather than each writing their own ownership test. Two copies would let a member be
 * shown an executable Pause that the command then refuses, which is the drift M9 forbids.
 *
 * <p>The rule is deliberately the same one the command has always applied: an active OWNER or
 * EXECUTOR responsibility for the attempt's WorkItem. Membership visibility is checked separately by
 * whoever loads the facts, because it decides whether the member sees the Task at all rather than
 * whether the button is live.
 */
public final class TaskControlAuthority {

    private TaskControlAuthority() {}

    /** Whether the principal currently holds an active OWNER or EXECUTOR responsibility. */
    public static boolean granted(
            PrincipalId actorPrincipalId, List<ResponsibilityAssignment> assignments) {
        PrincipalId requiredActor = Objects.requireNonNull(actorPrincipalId, "actorPrincipalId");
        return Objects.requireNonNull(assignments, "assignments").stream()
                .filter(ResponsibilityAssignment::isActive)
                .filter(value -> value.role() == ResponsibilityRole.OWNER
                        || value.role() == ResponsibilityRole.EXECUTOR)
                .anyMatch(value -> value.actorPrincipalId().equals(requiredActor));
    }

    /** Applies {@link #granted} as the command-side precondition. */
    public static void require(
            PrincipalId actorPrincipalId, List<ResponsibilityAssignment> assignments) {
        if (!granted(actorPrincipalId, assignments)) {
            throw new PolicyDeniedException("control this Task");
        }
    }
}
