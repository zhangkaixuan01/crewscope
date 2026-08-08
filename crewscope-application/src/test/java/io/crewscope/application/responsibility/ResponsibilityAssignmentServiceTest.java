package io.crewscope.application.responsibility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.responsibility.ActiveOwnerExpectation;
import io.crewscope.domain.responsibility.ResponsibilityAssignment;
import io.crewscope.domain.responsibility.ResponsibilityAssignmentId;
import io.crewscope.domain.responsibility.ResponsibilityAssignmentStatus;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.responsibility.ResponsibilityVersionConflictException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.team.TeamJoinMethod;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.team.TeamScope;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemKey;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class ResponsibilityAssignmentServiceTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-07T23:45:00Z");

    @Test
    void createsTheFirstOwnerOnlyWhenTheSlotIsExpectedToBeEmpty() {
        Fixture fixture = Fixture.create();

        OwnerAssignmentChange change = fixture.service.replaceOwner(
                fixture.workItem,
                fixture.owner,
                fixture.team.ownerMember(),
                fixture.owner,
                ActiveOwnerExpectation.none());

        assertTrue(change.released().isEmpty());
        assertEquals(ResponsibilityRole.OWNER, change.active().role());
        assertEquals(fixture.owner.id(), change.active().actorPrincipalId());
        assertEquals(1, fixture.repository.active(ResponsibilityRole.OWNER).size());
        assertEquals(1, fixture.transactions.calls);
    }

    @Test
    void atomicallyReleasesThePreviousOwnerAndCreatesItsSuccessor() {
        Fixture fixture = Fixture.create();
        ResponsibilityAssignment first = fixture.service
                .replaceOwner(
                        fixture.workItem,
                        fixture.owner,
                        fixture.team.ownerMember(),
                        fixture.owner,
                        ActiveOwnerExpectation.none())
                .active();
        Member second = fixture.member("Second");

        OwnerAssignmentChange change = fixture.service.replaceOwner(
                fixture.workItem,
                second.principal,
                second.membership,
                fixture.owner,
                ActiveOwnerExpectation.current(first));

        assertEquals(ResponsibilityAssignmentStatus.RELEASED, change.released().orElseThrow().status());
        assertEquals(second.principal.id(), change.active().actorPrincipalId());
        assertEquals(1, fixture.repository.active(ResponsibilityRole.OWNER).size());
        assertEquals(2, fixture.repository.values.size());
    }

    @Test
    void rejectsAnEmptyOrStaleOwnerExpectationWithoutWriting() {
        Fixture fixture = Fixture.create();
        ResponsibilityAssignment first = fixture.service
                .replaceOwner(
                        fixture.workItem,
                        fixture.owner,
                        fixture.team.ownerMember(),
                        fixture.owner,
                        ActiveOwnerExpectation.none())
                .active();
        Member second = fixture.member("Second");

        ResponsibilityVersionConflictException missingExpectation = assertThrows(
                ResponsibilityVersionConflictException.class,
                () -> fixture.service.replaceOwner(
                        fixture.workItem,
                        second.principal,
                        second.membership,
                        fixture.owner,
                        ActiveOwnerExpectation.none()));
        ResponsibilityVersionConflictException staleVersion = assertThrows(
                ResponsibilityVersionConflictException.class,
                () -> fixture.service.replaceOwner(
                        fixture.workItem,
                        second.principal,
                        second.membership,
                        fixture.owner,
                        ActiveOwnerExpectation.at(first.id(), first.version() + 1)));

        assertEquals("NONE", missingExpectation.error().details().get("expectedAssignmentId"));
        assertEquals(first.id().toString(), missingExpectation.error().details().get("actualAssignmentId"));
        assertEquals("1", staleVersion.error().details().get("expectedVersion"));
        assertEquals("0", staleVersion.error().details().get("actualVersion"));
        assertEquals(1, fixture.repository.values.size());
        assertTrue(first.isActive());
    }

    @Test
    void preventsAbaOwnerOverwriteAfterAnotherReplacement() {
        Fixture fixture = Fixture.create();
        ResponsibilityAssignment first = fixture.service
                .replaceOwner(
                        fixture.workItem,
                        fixture.owner,
                        fixture.team.ownerMember(),
                        fixture.owner,
                        ActiveOwnerExpectation.none())
                .active();
        ActiveOwnerExpectation staleWriter = ActiveOwnerExpectation.current(first);
        Member second = fixture.member("Second");
        ResponsibilityAssignment successor = fixture.service
                .replaceOwner(
                        fixture.workItem,
                        second.principal,
                        second.membership,
                        fixture.owner,
                        staleWriter)
                .active();
        Member third = fixture.member("Third");

        ResponsibilityVersionConflictException failure = assertThrows(
                ResponsibilityVersionConflictException.class,
                () -> fixture.service.replaceOwner(
                        fixture.workItem,
                        third.principal,
                        third.membership,
                        fixture.owner,
                        staleWriter));

        assertEquals(first.id().toString(), failure.error().details().get("expectedAssignmentId"));
        assertEquals(successor.id().toString(), failure.error().details().get("actualAssignmentId"));
        assertEquals(second.principal.id(), fixture.repository
                .active(ResponsibilityRole.OWNER)
                .get(0)
                .actorPrincipalId());
    }

    @Test
    void assignsMultipleExecutorsButRejectsTheSameActiveActorTwice() {
        Fixture fixture = Fixture.create();
        Member first = fixture.member("First executor");
        Member second = fixture.member("Second executor");

        ResponsibilityAssignment firstAssignment = fixture.service.assignExecutor(
                fixture.workItem,
                first.principal,
                Optional.of(first.membership),
                fixture.owner);
        fixture.service.assignExecutor(
                fixture.workItem,
                second.principal,
                Optional.of(second.membership),
                fixture.owner);

        assertEquals(2, fixture.repository.active(ResponsibilityRole.EXECUTOR).size());
        assertThrows(
                DomainValidationException.class,
                () -> fixture.service.assignExecutor(
                        fixture.workItem,
                        first.principal,
                        Optional.of(first.membership),
                        fixture.owner));
        assertTrue(firstAssignment.isActive());
        assertEquals(2, fixture.repository.active(ResponsibilityRole.EXECUTOR).size());
    }

    @Test
    void releasesExecutorAndAllowsAHistoryPreservingReassignment() {
        Fixture fixture = Fixture.create();
        Member executor = fixture.member("Executor");
        ResponsibilityAssignment first = fixture.service.assignExecutor(
                fixture.workItem,
                executor.principal,
                Optional.of(executor.membership),
                fixture.owner);

        ResponsibilityAssignment released = fixture.service.release(
                fixture.organizationId, first.id(), first.version(), fixture.owner);
        ResponsibilityAssignment reassigned = fixture.service.assignExecutor(
                fixture.workItem,
                executor.principal,
                Optional.of(executor.membership),
                fixture.owner);

        assertEquals(ResponsibilityAssignmentStatus.RELEASED, released.status());
        assertEquals(1, released.version());
        assertTrue(reassigned.isActive());
        assertEquals(2, fixture.repository.values.size());
        assertEquals(1, fixture.repository.active(ResponsibilityRole.EXECUTOR).size());
    }

    @Test
    void rejectsStaleReleaseAndDirectOwnerRelease() {
        Fixture fixture = Fixture.create();
        Member executor = fixture.member("Executor");
        ResponsibilityAssignment executorAssignment = fixture.service.assignExecutor(
                fixture.workItem,
                executor.principal,
                Optional.of(executor.membership),
                fixture.owner);
        ResponsibilityAssignment ownerAssignment = fixture.service
                .replaceOwner(
                        fixture.workItem,
                        fixture.owner,
                        fixture.team.ownerMember(),
                        fixture.owner,
                        ActiveOwnerExpectation.none())
                .active();

        assertThrows(
                OptimisticLockConflictException.class,
                () -> fixture.service.release(
                        fixture.organizationId,
                        executorAssignment.id(),
                        executorAssignment.version() + 1,
                        fixture.owner));
        assertThrows(
                DomainValidationException.class,
                () -> fixture.service.release(
                        fixture.organizationId,
                        ownerAssignment.id(),
                        ownerAssignment.version(),
                        fixture.owner));
        assertTrue(fixture.repository.values.get(executorAssignment.id()).isActive());
        assertTrue(fixture.repository.values.get(ownerAssignment.id()).isActive());
    }

    @Test
    void assignsOnlySpecialistAgentsAsAdvisoryReviewers() {
        Fixture fixture = Fixture.create();
        Principal specialist = fixture.specialistAgent();

        ResponsibilityAssignment reviewer = fixture.service.assignAdvisoryReviewer(
                fixture.workItem, specialist, fixture.owner);

        assertEquals(ResponsibilityRole.REVIEWER, reviewer.role());
        assertEquals(specialist.id(), reviewer.actorPrincipalId());
        assertThrows(
                DomainValidationException.class,
                () -> fixture.service.assignAdvisoryReviewer(
                        fixture.workItem, fixture.owner, fixture.owner));
    }

    @Test
    void preventsAnActiveHumanReviewerFromBecomingOwnerOrExecutor() {
        Fixture fixture = Fixture.create();
        Member reviewer = fixture.member("Gate reviewer");
        fixture.repository.create(ResponsibilityAssignment.assign(
                ResponsibilityAssignmentId.generate(),
                fixture.workItem,
                ResponsibilityRole.REVIEWER,
                reviewer.principal,
                Optional.of(reviewer.membership),
                fixture.owner,
                NOW));

        assertThrows(
                DomainValidationException.class,
                () -> fixture.service.replaceOwner(
                        fixture.workItem,
                        reviewer.principal,
                        reviewer.membership,
                        fixture.owner,
                        ActiveOwnerExpectation.none()));
        assertThrows(
                DomainValidationException.class,
                () -> fixture.service.assignExecutor(
                        fixture.workItem,
                        reviewer.principal,
                        Optional.of(reviewer.membership),
                        fixture.owner));

        assertEquals(1, fixture.repository.values.size());
        assertEquals(2, fixture.repository.lockCalls);
    }

    private record Member(Principal principal, TeamMember membership) {}

    private static final class Fixture {

        private final OrganizationId organizationId;
        private final Principal owner;
        private final TeamInitialization team;
        private final WorkItem workItem;
        private final InMemoryRepository repository;
        private final CountingTransactionExecutor transactions;
        private final ResponsibilityAssignmentService service;

        private Fixture(
                OrganizationId organizationId,
                Principal owner,
                TeamInitialization team,
                WorkItem workItem,
                InMemoryRepository repository,
                CountingTransactionExecutor transactions,
                ResponsibilityAssignmentService service) {
            this.organizationId = organizationId;
            this.owner = owner;
            this.team = team;
            this.workItem = workItem;
            this.repository = repository;
            this.transactions = transactions;
            this.service = service;
        }

        private static Fixture create() {
            OrganizationId organizationId = OrganizationId.generate();
            Principal owner = activeUser(organizationId, "Owner");
            TeamInitialization team = TeamInitialization.create(owner, "Platform", NOW);
            WorkItem workItem = WorkItem.create(
                    WorkItemId.generate(),
                    new WorkItemScope(
                            organizationId,
                            team.team().id(),
                            team.defaultWorkspace().id(),
                            WorkProjectId.generate()),
                    new WorkItemKey("CRW-1"),
                    "Responsibility service",
                    owner.id(),
                    NOW);
            InMemoryRepository repository = new InMemoryRepository();
            CountingTransactionExecutor transactions = new CountingTransactionExecutor();
            ResponsibilityAssignmentService service = new ResponsibilityAssignmentService(
                    repository, transactions, () -> NOW);
            return new Fixture(
                    organizationId, owner, team, workItem, repository, transactions, service);
        }

        private Member member(String name) {
            Principal principal = activeUser(organizationId, name);
            TeamMember membership = TeamMember.join(
                    TeamMemberId.generate(),
                    new TeamScope(organizationId, team.team().id()),
                    principal,
                    TeamJoinMethod.OIDC,
                    NOW);
            return new Member(principal, membership);
        }

        private Principal specialistAgent() {
            return Principal.create(
                    PrincipalId.generate(),
                    PrincipalScope.team(organizationId, team.team().id()),
                    PrincipalType.SPECIALIST_AGENT,
                    Optional.of(owner.id()),
                    "Reviewer Specialist",
                    Optional.empty(),
                    PrincipalVisibility.TEAM,
                    NOW);
        }

        private static Principal activeUser(OrganizationId organizationId, String name) {
            return Principal.create(
                    PrincipalId.generate(),
                    PrincipalScope.organization(organizationId),
                    PrincipalType.USER,
                    Optional.empty(),
                    name,
                    Optional.empty(),
                    PrincipalVisibility.ORGANIZATION,
                    NOW);
        }
    }

    private static final class InMemoryRepository
            implements ResponsibilityAssignmentRepository {

        private final Map<ResponsibilityAssignmentId, ResponsibilityAssignment> values =
                new LinkedHashMap<>();
        private int lockCalls;

        @Override
        public void lockResponsibilityChain(
                OrganizationId organizationId, WorkItemId workItemId) {
            lockCalls++;
        }

        @Override
        public ResponsibilityAssignment create(ResponsibilityAssignment assignment) {
            boolean occupied = active(assignment.role()).stream().anyMatch(existing ->
                    assignment.role() == ResponsibilityRole.OWNER
                            || existing.actorPrincipalId().equals(assignment.actorPrincipalId()));
            if (occupied) {
                throw new IllegalStateException("active responsibility slot already occupied");
            }
            values.put(assignment.id(), assignment);
            return assignment;
        }

        @Override
        public ResponsibilityAssignment update(ResponsibilityAssignment assignment) {
            ResponsibilityAssignment committed = values.get(assignment.id());
            long expectedVersion = assignment.version() - 1;
            if (committed == null || committed.version() != expectedVersion) {
                throw new OptimisticLockConflictException(
                        "ResponsibilityAssignment",
                        assignment.id(),
                        expectedVersion,
                        committed == null ? 0 : committed.version());
            }
            values.put(assignment.id(), assignment);
            return assignment;
        }

        @Override
        public Optional<ResponsibilityAssignment> findById(
                OrganizationId organizationId, ResponsibilityAssignmentId id) {
            return Optional.ofNullable(values.get(id))
                    .filter(value -> value.scope().organizationId().equals(organizationId));
        }

        @Override
        public Optional<ResponsibilityAssignment> findActiveOwner(
                OrganizationId organizationId, WorkItemId workItemId) {
            return values.values().stream()
                    .filter(ResponsibilityAssignment::isActive)
                    .filter(value -> value.role() == ResponsibilityRole.OWNER)
                    .filter(value -> value.scope().organizationId().equals(organizationId))
                    .filter(value -> value.workItemId().equals(workItemId))
                    .findFirst();
        }

        @Override
        public List<ResponsibilityAssignment> findActiveByWorkItem(
                OrganizationId organizationId, WorkItemId workItemId) {
            return values.values().stream()
                    .filter(ResponsibilityAssignment::isActive)
                    .filter(value -> value.scope().organizationId().equals(organizationId))
                    .filter(value -> value.workItemId().equals(workItemId))
                    .toList();
        }

        @Override
        public Optional<ResponsibilityAssignment> findActive(
                OrganizationId organizationId,
                WorkItemId workItemId,
                ResponsibilityRole role,
                PrincipalId actorPrincipalId) {
            return values.values().stream()
                    .filter(ResponsibilityAssignment::isActive)
                    .filter(value -> value.role() == role)
                    .filter(value -> value.scope().organizationId().equals(organizationId))
                    .filter(value -> value.workItemId().equals(workItemId))
                    .filter(value -> value.actorPrincipalId().equals(actorPrincipalId))
                    .findFirst();
        }

        private List<ResponsibilityAssignment> active(ResponsibilityRole role) {
            List<ResponsibilityAssignment> result = new ArrayList<>();
            values.values().stream()
                    .filter(ResponsibilityAssignment::isActive)
                    .filter(value -> value.role() == role)
                    .forEach(result::add);
            return result;
        }
    }

    private static final class CountingTransactionExecutor implements TransactionExecutor {

        private int calls;

        @Override
        public <T> T required(Supplier<T> operation) {
            calls++;
            return operation.get();
        }
    }
}
