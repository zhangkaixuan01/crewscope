package io.crewscope.application.responsibility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.policy.PolicyPackId;
import io.crewscope.domain.policy.PolicyPackReference;
import io.crewscope.domain.responsibility.ResponsibilityAssignment;
import io.crewscope.domain.responsibility.ResponsibilityAssignmentId;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.responsibility.ReviewerEligibilityMode;
import io.crewscope.domain.responsibility.ReviewerEligibilityPolicy;
import io.crewscope.domain.responsibility.ReviewerPolicyViolationException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.team.TeamJoinMethod;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
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

class GateReviewerAssignmentServiceTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-08T00:30:00Z");

    @Test
    void assignsAnIndependentGateReviewerFromServerSideFacts() {
        Fixture fixture = Fixture.create();
        Member reviewer = fixture.member("Reviewer");
        fixture.membershipQuery.members = List.of(fixture.team.ownerMember(), reviewer.membership);
        fixture.repository.seed(fixture.assignment(
                ResponsibilityRole.OWNER,
                fixture.owner,
                Optional.of(fixture.team.ownerMember())));

        GateReviewerAssignment result = fixture.service.assignGateReviewer(
                fixture.workItem,
                reviewer.principal,
                reviewer.membership,
                fixture.owner,
                ReviewerEligibilityPolicy.strict());

        assertEquals(ResponsibilityRole.REVIEWER, result.assignment().role());
        assertEquals(reviewer.principal.id(), result.assignment().actorPrincipalId());
        assertEquals(ReviewerEligibilityMode.STRICT_SEPARATION, result.eligibility().mode());
        assertEquals(fixture.organizationId, fixture.membershipQuery.organizationId);
        assertEquals(fixture.team.team().id(), fixture.membershipQuery.teamId);
        assertEquals(1, fixture.transactions.calls);
        assertEquals(1, fixture.repository.lockCalls);
    }

    @Test
    void doesNotPersistWhenStrictDutySeparationRejectsTheReviewer() {
        Fixture fixture = Fixture.create();
        fixture.membershipQuery.members = List.of(fixture.team.ownerMember());
        fixture.repository.seed(fixture.assignment(
                ResponsibilityRole.OWNER,
                fixture.owner,
                Optional.of(fixture.team.ownerMember())));

        assertThrows(
                ReviewerPolicyViolationException.class,
                () -> fixture.service.assignGateReviewer(
                        fixture.workItem,
                        fixture.owner,
                        fixture.team.ownerMember(),
                        fixture.owner,
                        ReviewerEligibilityPolicy.strict()));

        assertEquals(1, fixture.repository.values.size());
        assertTrue(fixture.repository.active(ResponsibilityRole.REVIEWER).isEmpty());
    }

    @Test
    void returnsAuditEvidenceWhenSingleMemberPolicyAllowsSelfReview() {
        Fixture fixture = Fixture.create();
        fixture.membershipQuery.members = List.of(fixture.team.ownerMember());
        fixture.repository.seed(fixture.assignment(
                ResponsibilityRole.OWNER,
                fixture.owner,
                Optional.of(fixture.team.ownerMember())));
        PolicyPackReference policyPack =
                new PolicyPackReference(PolicyPackId.generate(), 3);

        GateReviewerAssignment result = fixture.service.assignGateReviewer(
                fixture.workItem,
                fixture.owner,
                fixture.team.ownerMember(),
                fixture.owner,
                ReviewerEligibilityPolicy.withSingleMemberOverride(
                        policyPack, "Sole maintainer emergency review"));

        assertEquals(ReviewerEligibilityMode.SINGLE_MEMBER_OVERRIDE, result.eligibility().mode());
        assertEquals(policyPack, result.eligibility().policyPack().orElseThrow());
        assertEquals(
                "Sole maintainer emergency review",
                result.eligibility().overrideReason().orElseThrow());
        assertEquals(1, fixture.repository.active(ResponsibilityRole.REVIEWER).size());
    }

    @Test
    void rejectsAReviewerWhoBecameInactiveBeforePolicyEvaluation() {
        Fixture fixture = Fixture.create();
        Member reviewer = fixture.member("Reviewer");
        TeamMember suspended = reviewer.membership.suspend(NOW);
        fixture.membershipQuery.members = List.of(fixture.team.ownerMember(), suspended);

        assertThrows(
                DomainValidationException.class,
                () -> fixture.service.assignGateReviewer(
                        fixture.workItem,
                        reviewer.principal,
                        suspended,
                        fixture.owner,
                        ReviewerEligibilityPolicy.strict()));

        assertTrue(fixture.repository.values.isEmpty());
    }

    @Test
    void failsClosedWhenTheMembershipPortReturnsAnotherTeam() {
        Fixture fixture = Fixture.create();
        Member reviewer = fixture.member("Reviewer");
        Principal outsider = Fixture.activeUser(fixture.organizationId, "Outsider");
        TeamInitialization otherTeam = TeamInitialization.create(outsider, "Other", NOW);
        fixture.membershipQuery.members =
                List.of(fixture.team.ownerMember(), reviewer.membership, otherTeam.ownerMember());

        DomainValidationException failure = assertThrows(
                DomainValidationException.class,
                () -> fixture.service.assignGateReviewer(
                        fixture.workItem,
                        reviewer.principal,
                        reviewer.membership,
                        fixture.owner,
                        ReviewerEligibilityPolicy.strict()));

        assertEquals(
                "reviewerEligibilityPolicy.teamMembers",
                failure.error().details().get("field"));
        assertTrue(fixture.repository.values.isEmpty());
    }

    @Test
    void rejectsARepeatedActiveGateReviewerWithoutCreatingHistoryNoise() {
        Fixture fixture = Fixture.create();
        Member reviewer = fixture.member("Reviewer");
        fixture.membershipQuery.members = List.of(fixture.team.ownerMember(), reviewer.membership);
        fixture.service.assignGateReviewer(
                fixture.workItem,
                reviewer.principal,
                reviewer.membership,
                fixture.owner,
                ReviewerEligibilityPolicy.strict());

        assertThrows(
                DomainValidationException.class,
                () -> fixture.service.assignGateReviewer(
                        fixture.workItem,
                        reviewer.principal,
                        reviewer.membership,
                        fixture.owner,
                        ReviewerEligibilityPolicy.strict()));

        assertEquals(1, fixture.repository.values.size());
        assertEquals(1, fixture.repository.active(ResponsibilityRole.REVIEWER).size());
    }

    private record Member(Principal principal, TeamMember membership) {}

    private static final class Fixture {

        private final OrganizationId organizationId;
        private final Principal owner;
        private final TeamInitialization team;
        private final WorkItem workItem;
        private final InMemoryRepository repository;
        private final RecordingMembershipQuery membershipQuery;
        private final CountingTransactionExecutor transactions;
        private final GateReviewerAssignmentService service;

        private Fixture(
                OrganizationId organizationId,
                Principal owner,
                TeamInitialization team,
                WorkItem workItem,
                InMemoryRepository repository,
                RecordingMembershipQuery membershipQuery,
                CountingTransactionExecutor transactions,
                GateReviewerAssignmentService service) {
            this.organizationId = organizationId;
            this.owner = owner;
            this.team = team;
            this.workItem = workItem;
            this.repository = repository;
            this.membershipQuery = membershipQuery;
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
                    "Gate reviewer assignment",
                    owner.id(),
                    NOW);
            InMemoryRepository repository = new InMemoryRepository();
            RecordingMembershipQuery membershipQuery = new RecordingMembershipQuery();
            CountingTransactionExecutor transactions = new CountingTransactionExecutor();
            GateReviewerAssignmentService service = new GateReviewerAssignmentService(
                    repository, membershipQuery, transactions, () -> NOW);
            return new Fixture(
                    organizationId,
                    owner,
                    team,
                    workItem,
                    repository,
                    membershipQuery,
                    transactions,
                    service);
        }

        private Member member(String name) {
            Principal principal = activeUser(organizationId, name);
            TeamMember membership = team.team().joinMember(
                    TeamMemberId.generate(), principal, TeamJoinMethod.OIDC, NOW);
            return new Member(principal, membership);
        }

        private ResponsibilityAssignment assignment(
                ResponsibilityRole role,
                Principal actor,
                Optional<TeamMember> member) {
            return ResponsibilityAssignment.assign(
                    ResponsibilityAssignmentId.generate(),
                    workItem,
                    role,
                    actor,
                    member,
                    owner,
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

    private static final class RecordingMembershipQuery implements TeamMembershipQuery {

        private List<TeamMember> members = List.of();
        private OrganizationId organizationId;
        private TeamId teamId;

        @Override
        public List<TeamMember> findByTeam(OrganizationId organizationId, TeamId teamId) {
            this.organizationId = organizationId;
            this.teamId = teamId;
            return members;
        }
    }

    private static final class InMemoryRepository
            implements ResponsibilityAssignmentRepository {

        private final Map<ResponsibilityAssignmentId, ResponsibilityAssignment> values =
                new LinkedHashMap<>();
        private int lockCalls;

        private void seed(ResponsibilityAssignment assignment) {
            values.put(assignment.id(), assignment);
        }

        @Override
        public void lockResponsibilityChain(
                OrganizationId organizationId, WorkItemId workItemId) {
            lockCalls++;
        }

        @Override
        public ResponsibilityAssignment create(ResponsibilityAssignment assignment) {
            values.put(assignment.id(), assignment);
            return assignment;
        }

        @Override
        public ResponsibilityAssignment update(ResponsibilityAssignment assignment) {
            ResponsibilityAssignment current = values.get(assignment.id());
            long expectedVersion = assignment.version() - 1;
            if (current == null || current.version() != expectedVersion) {
                throw new OptimisticLockConflictException(
                        "ResponsibilityAssignment",
                        assignment.id(),
                        expectedVersion,
                        current == null ? 0 : current.version());
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
            return activeByWorkItem(organizationId, workItemId).stream()
                    .filter(value -> value.role() == ResponsibilityRole.OWNER)
                    .findFirst();
        }

        @Override
        public List<ResponsibilityAssignment> findActiveByWorkItem(
                OrganizationId organizationId, WorkItemId workItemId) {
            return activeByWorkItem(organizationId, workItemId);
        }

        @Override
        public Optional<ResponsibilityAssignment> findActive(
                OrganizationId organizationId,
                WorkItemId workItemId,
                ResponsibilityRole role,
                PrincipalId actorPrincipalId) {
            return activeByWorkItem(organizationId, workItemId).stream()
                    .filter(value -> value.role() == role)
                    .filter(value -> value.actorPrincipalId().equals(actorPrincipalId))
                    .findFirst();
        }

        private List<ResponsibilityAssignment> activeByWorkItem(
                OrganizationId organizationId, WorkItemId workItemId) {
            return values.values().stream()
                    .filter(ResponsibilityAssignment::isActive)
                    .filter(value -> value.scope().organizationId().equals(organizationId))
                    .filter(value -> value.workItemId().equals(workItemId))
                    .toList();
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
