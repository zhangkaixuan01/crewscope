package io.crewscope.application.responsibility;

import io.crewscope.domain.responsibility.ResponsibilityAssignment;
import io.crewscope.domain.responsibility.ResponsibilityAssignmentId;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.workitem.WorkItemId;
import java.util.List;
import java.util.Optional;

/** Persistence Port for tenant-scoped ResponsibilityAssignment facts. */
public interface ResponsibilityAssignmentRepository {

    /**
     * Serializes policy-sensitive responsibility changes for one WorkItem. D08 implements this
     * boundary with a WorkItem row lock before reading the responsibility chain.
     */
    void lockResponsibilityChain(OrganizationId organizationId, WorkItemId workItemId);

    /** Inserts one active fact; implementations map active-slot uniqueness races to a conflict. */
    ResponsibilityAssignment create(ResponsibilityAssignment assignment);

    /** Commits a release using the assignment's previous version as the lock predicate. */
    ResponsibilityAssignment update(ResponsibilityAssignment assignment);

    Optional<ResponsibilityAssignment> findById(
            OrganizationId organizationId, ResponsibilityAssignmentId id);

    Optional<ResponsibilityAssignment> findActiveOwner(
            OrganizationId organizationId, WorkItemId workItemId);

    /** Returns all active responsibility facts used for policy evaluation. */
    List<ResponsibilityAssignment> findActiveByWorkItem(
            OrganizationId organizationId, WorkItemId workItemId);

    Optional<ResponsibilityAssignment> findActive(
            OrganizationId organizationId,
            WorkItemId workItemId,
            ResponsibilityRole role,
            PrincipalId actorPrincipalId);
}
