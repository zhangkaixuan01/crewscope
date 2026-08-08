package io.crewscope.application.responsibility;

import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.responsibility.ActiveOwnerExpectation;
import io.crewscope.domain.responsibility.ResponsibilityAssignment;
import io.crewscope.domain.responsibility.ResponsibilityAssignmentId;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.responsibility.ResponsibilityVersionConflictException;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.workitem.WorkItem;
import java.util.Objects;
import java.util.Optional;

/** Coordinates responsibility slot invariants and assignment lifecycle in one transaction. */
public final class ResponsibilityAssignmentService {

    private final ResponsibilityAssignmentRepository repository;
    private final TransactionExecutor transactionExecutor;
    private final TimeProvider timeProvider;

    public ResponsibilityAssignmentService(
            ResponsibilityAssignmentRepository repository,
            TransactionExecutor transactionExecutor,
            TimeProvider timeProvider) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.transactionExecutor =
                Objects.requireNonNull(transactionExecutor, "transactionExecutor");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    /**
     * Atomically releases the current Owner and creates its successor. A missing expected identity
     * means that the caller expects the WorkItem to have no active Owner yet.
     */
    public OwnerAssignmentChange replaceOwner(
            WorkItem workItem,
            Principal owner,
            TeamMember ownerMember,
            Principal assignedBy,
            ActiveOwnerExpectation expectation) {
        WorkItem requiredWorkItem = Objects.requireNonNull(workItem, "workItem");
        Principal requiredOwner = Objects.requireNonNull(owner, "owner");
        TeamMember requiredMember = Objects.requireNonNull(ownerMember, "ownerMember");
        Principal requiredAssigner = Objects.requireNonNull(assignedBy, "assignedBy");
        ActiveOwnerExpectation requiredExpectation =
                Objects.requireNonNull(expectation, "expectation");
        return transactionExecutor.required(() -> replaceOwnerInTransaction(
                requiredWorkItem,
                requiredOwner,
                requiredMember,
                requiredAssigner,
                requiredExpectation,
                timeProvider.now()));
    }

    /** Assigns an eligible Team member or Agent as an Executor. */
    public ResponsibilityAssignment assignExecutor(
            WorkItem workItem,
            Principal executor,
            Optional<TeamMember> executorMember,
            Principal assignedBy) {
        return assignNonOwner(
                workItem,
                ResponsibilityRole.EXECUTOR,
                executor,
                executorMember,
                assignedBy);
    }

    /** Assigns a Specialist Agent whose review output can only have advisory effect. */
    public ResponsibilityAssignment assignAdvisoryReviewer(
            WorkItem workItem,
            Principal reviewer,
            Principal assignedBy) {
        Principal requiredReviewer = Objects.requireNonNull(reviewer, "reviewer");
        if (requiredReviewer.type() != PrincipalType.SPECIALIST_AGENT) {
            throw new DomainValidationException(
                    "responsibilityAssignment.actorPrincipalId",
                    "advisory Reviewer must be a SPECIALIST_AGENT Principal");
        }
        return assignNonOwner(
                workItem,
                ResponsibilityRole.REVIEWER,
                requiredReviewer,
                Optional.empty(),
                assignedBy);
    }

    /** Releases an Executor or Reviewer using an explicit optimistic-lock version. */
    public ResponsibilityAssignment release(
            OrganizationId organizationId,
            ResponsibilityAssignmentId assignmentId,
            long expectedVersion,
            Principal releasedBy) {
        OrganizationId requiredOrganization =
                Objects.requireNonNull(organizationId, "organizationId");
        ResponsibilityAssignmentId requiredId =
                Objects.requireNonNull(assignmentId, "assignmentId");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
        Principal requiredReleaser = Objects.requireNonNull(releasedBy, "releasedBy");
        return transactionExecutor.required(() -> releaseInTransaction(
                requiredOrganization,
                requiredId,
                expectedVersion,
                requiredReleaser,
                timeProvider.now()));
    }

    private OwnerAssignmentChange replaceOwnerInTransaction(
            WorkItem workItem,
            Principal owner,
            TeamMember ownerMember,
            Principal assignedBy,
            ActiveOwnerExpectation expectation,
            UtcTimestamp occurredAt) {
        repository.lockResponsibilityChain(
                workItem.scope().organizationId(), workItem.id());
        Optional<ResponsibilityAssignment> current = repository.findActiveOwner(
                        workItem.scope().organizationId(), workItem.id())
                .map(value -> requireActiveOwnerResult(workItem, value));
        requireExpectedOwner(workItem, current, expectation);
        requireNotActiveGateReviewer(workItem, owner);
        current.filter(existing -> existing.actorPrincipalId().equals(owner.id()))
                .ifPresent(existing -> {
                    throw new DomainValidationException(
                            "responsibilityAssignment.actorPrincipalId",
                            "is already the active WorkItem Owner");
                });

        ResponsibilityAssignment candidate = ResponsibilityAssignment.assign(
                ResponsibilityAssignmentId.generate(),
                workItem,
                ResponsibilityRole.OWNER,
                owner,
                Optional.of(ownerMember),
                assignedBy,
                occurredAt);
        Optional<ResponsibilityAssignment> released = current.map(existing ->
                repository.update(existing.release(assignedBy, occurredAt)));
        ResponsibilityAssignment active = repository.create(candidate);
        return new OwnerAssignmentChange(released, active);
    }

    private ResponsibilityAssignment assignNonOwner(
            WorkItem workItem,
            ResponsibilityRole role,
            Principal actor,
            Optional<TeamMember> actorMember,
            Principal assignedBy) {
        WorkItem requiredWorkItem = Objects.requireNonNull(workItem, "workItem");
        Principal requiredActor = Objects.requireNonNull(actor, "actor");
        Optional<TeamMember> requiredMember = Objects.requireNonNull(actorMember, "actorMember");
        Principal requiredAssigner = Objects.requireNonNull(assignedBy, "assignedBy");
        return transactionExecutor.required(() -> {
            UtcTimestamp occurredAt = timeProvider.now();
            if (role == ResponsibilityRole.EXECUTOR) {
                repository.lockResponsibilityChain(
                        requiredWorkItem.scope().organizationId(), requiredWorkItem.id());
                requireNotActiveGateReviewer(requiredWorkItem, requiredActor);
            }
            ResponsibilityAssignment candidate = ResponsibilityAssignment.assign(
                    ResponsibilityAssignmentId.generate(),
                    requiredWorkItem,
                    role,
                    requiredActor,
                    requiredMember,
                    requiredAssigner,
                    occurredAt);
            repository.findActive(
                            requiredWorkItem.scope().organizationId(),
                            requiredWorkItem.id(),
                            role,
                            requiredActor.id())
                    .ifPresent(existing -> {
                        throw new DomainValidationException(
                                "responsibilityAssignment.actorPrincipalId",
                                "already has an active " + role + " assignment");
                    });
            return repository.create(candidate);
        });
    }

    private ResponsibilityAssignment releaseInTransaction(
            OrganizationId organizationId,
            ResponsibilityAssignmentId assignmentId,
            long expectedVersion,
            Principal releasedBy,
            UtcTimestamp occurredAt) {
        ResponsibilityAssignment current = repository.findById(organizationId, assignmentId)
                .orElseThrow(() -> new AggregateNotFoundException(
                        "ResponsibilityAssignment", assignmentId));
        if (current.role() == ResponsibilityRole.OWNER) {
            throw new DomainValidationException(
                    "responsibilityAssignment.role",
                    "OWNER must be changed through atomic replacement");
        }
        if (current.version() != expectedVersion) {
            throw new OptimisticLockConflictException(
                    "ResponsibilityAssignment",
                    current.id(),
                    expectedVersion,
                    current.version());
        }
        return repository.update(current.release(releasedBy, occurredAt));
    }

    /** Prevents Owner/Executor changes from bypassing the Gate Reviewer separation policy. */
    private void requireNotActiveGateReviewer(WorkItem workItem, Principal candidate) {
        if (candidate.type() != PrincipalType.USER) {
            return;
        }
        repository.findActive(
                        workItem.scope().organizationId(),
                        workItem.id(),
                        ResponsibilityRole.REVIEWER,
                        candidate.id())
                .ifPresent(existing -> {
                    throw new DomainValidationException(
                            "responsibilityAssignment.actorPrincipalId",
                            "an active Gate Reviewer cannot become Owner or Executor");
                });
    }

    private static void requireExpectedOwner(
            WorkItem workItem,
            Optional<ResponsibilityAssignment> current,
            ActiveOwnerExpectation expectation) {
        boolean matches = current
                .map(actual -> expectation.assignmentId().filter(actual.id()::equals).isPresent()
                        && expectation.assignmentVersion() == actual.version())
                .orElseGet(() -> expectation.assignmentId().isEmpty());
        if (!matches) {
            throw new ResponsibilityVersionConflictException(
                    workItem.id(), ResponsibilityRole.OWNER, expectation, current);
        }
    }

    /** Fails closed if a persistence adapter crosses a tenant, subject or active-role boundary. */
    private static ResponsibilityAssignment requireActiveOwnerResult(
            WorkItem workItem, ResponsibilityAssignment assignment) {
        if (!assignment.isActive()
                || assignment.role() != ResponsibilityRole.OWNER
                || !assignment.workItemId().equals(workItem.id())
                || !assignment.scope().equals(workItem.scope())) {
            throw new DomainValidationException(
                    "responsibilityAssignment.repositoryResult",
                    "must be the active Owner of the expected WorkItem scope");
        }
        return assignment;
    }
}
