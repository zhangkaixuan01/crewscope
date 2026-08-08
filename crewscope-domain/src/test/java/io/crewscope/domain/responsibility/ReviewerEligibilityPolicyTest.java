package io.crewscope.domain.responsibility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.policy.PolicyPackId;
import io.crewscope.domain.policy.PolicyPackReference;
import io.crewscope.domain.shared.error.DomainErrorCategory;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ReviewerEligibilityPolicyTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-08T00:00:00Z");
    private static final PolicyPackReference POLICY_PACK =
            new PolicyPackReference(PolicyPackId.generate(), 7);

    @Test
    void acceptsAnIndependentActiveTeamMemberUnderStrictSeparation() {
        Fixture fixture = Fixture.create();
        Member reviewer = fixture.member("Reviewer");
        ResponsibilityAssignment owner = fixture.assignment(
                ResponsibilityRole.OWNER,
                fixture.owner,
                Optional.of(fixture.team.ownerMember()));

        ReviewerEligibilityDecision decision = ReviewerEligibilityPolicy.strict()
                .evaluateGate(
                        fixture.workItem,
                        reviewer.principal,
                        reviewer.membership,
                        List.of(fixture.team.ownerMember(), reviewer.membership),
                        List.of(owner));

        assertEquals(ReviewerEligibilityMode.STRICT_SEPARATION, decision.mode());
        assertFalse(decision.degraded());
        assertTrue(decision.conflictingRoles().isEmpty());
        assertTrue(decision.policyPack().isEmpty());
    }

    @Test
    void rejectsTheActiveOwnerAsGateReviewerByDefault() {
        Fixture fixture = Fixture.create();
        ResponsibilityAssignment owner = fixture.assignment(
                ResponsibilityRole.OWNER,
                fixture.owner,
                Optional.of(fixture.team.ownerMember()));

        ReviewerPolicyViolationException failure = assertThrows(
                ReviewerPolicyViolationException.class,
                () -> ReviewerEligibilityPolicy.strict()
                        .evaluateGate(
                                fixture.workItem,
                                fixture.owner,
                                fixture.team.ownerMember(),
                                List.of(fixture.team.ownerMember()),
                                List.of(owner)));

        assertEquals(DomainErrorCategory.POLICY, failure.error().category());
        assertEquals("OWNER", failure.error().details().get("conflictingRoles"));
        assertEquals("1", failure.error().details().get("activeMemberCount"));
        assertEquals(
                fixture.team.ownerMember().id().toString(),
                failure.error().details().get("reviewerMemberId"));
    }

    @Test
    void rejectsAnActiveExecutorAsGateReviewerByDefault() {
        Fixture fixture = Fixture.create();
        Member reviewer = fixture.member("Reviewer executor");
        ResponsibilityAssignment executor = fixture.assignment(
                ResponsibilityRole.EXECUTOR,
                reviewer.principal,
                Optional.of(reviewer.membership));

        ReviewerPolicyViolationException failure = assertThrows(
                ReviewerPolicyViolationException.class,
                () -> ReviewerEligibilityPolicy.strict()
                        .evaluateGate(
                                fixture.workItem,
                                reviewer.principal,
                                reviewer.membership,
                                List.of(fixture.team.ownerMember(), reviewer.membership),
                                List.of(executor)));

        assertEquals("EXECUTOR", failure.error().details().get("conflictingRoles"));
    }

    @Test
    void ignoresReleasedOwnerAndExecutorHistory() {
        Fixture fixture = Fixture.create();
        ResponsibilityAssignment owner = fixture.assignment(
                ResponsibilityRole.OWNER,
                fixture.owner,
                Optional.of(fixture.team.ownerMember()));
        ResponsibilityAssignment executor = fixture.assignment(
                ResponsibilityRole.EXECUTOR,
                fixture.owner,
                Optional.of(fixture.team.ownerMember()));

        ReviewerEligibilityDecision decision = ReviewerEligibilityPolicy.strict()
                .evaluateGate(
                        fixture.workItem,
                        fixture.owner,
                        fixture.team.ownerMember(),
                        List.of(fixture.team.ownerMember()),
                        List.of(owner.release(fixture.owner, NOW), executor.release(fixture.owner, NOW)));

        assertEquals(ReviewerEligibilityMode.STRICT_SEPARATION, decision.mode());
    }

    @Test
    void rejectsAnInactiveReviewerMembership() {
        Fixture fixture = Fixture.create();
        Member reviewer = fixture.member("Suspended reviewer");
        TeamMember suspended = reviewer.membership.suspend(NOW);

        DomainValidationException failure = assertThrows(
                DomainValidationException.class,
                () -> ReviewerEligibilityPolicy.strict()
                        .evaluateGate(
                                fixture.workItem,
                                reviewer.principal,
                                suspended,
                                List.of(fixture.team.ownerMember(), suspended),
                                List.of()));

        assertEquals(
                "reviewerEligibilityPolicy.reviewerMemberId",
                failure.error().details().get("field"));
    }

    @Test
    void neverAllowsAnAgentToReceiveGateAuthority() {
        Fixture fixture = Fixture.create();
        Principal specialist = fixture.specialistAgent();

        assertThrows(
                DomainValidationException.class,
                () -> ReviewerEligibilityPolicy.strict()
                        .evaluateGate(
                                fixture.workItem,
                                specialist,
                                fixture.team.ownerMember(),
                                List.of(fixture.team.ownerMember()),
                                List.of()));
    }

    @Test
    void failsClosedForResponsibilityFactsFromAnotherWorkItem() {
        Fixture fixture = Fixture.create();
        Member reviewer = fixture.member("Reviewer");
        WorkItem other = fixture.otherWorkItem();
        ResponsibilityAssignment wrongSubject = ResponsibilityAssignment.assign(
                ResponsibilityAssignmentId.generate(),
                other,
                ResponsibilityRole.OWNER,
                fixture.owner,
                Optional.of(fixture.team.ownerMember()),
                fixture.owner,
                NOW);

        DomainValidationException failure = assertThrows(
                DomainValidationException.class,
                () -> ReviewerEligibilityPolicy.strict()
                        .evaluateGate(
                                fixture.workItem,
                                reviewer.principal,
                                reviewer.membership,
                                List.of(fixture.team.ownerMember(), reviewer.membership),
                                List.of(wrongSubject)));

        assertEquals(
                "reviewerEligibilityPolicy.assignments",
                failure.error().details().get("field"));
    }

    @Test
    void recordsPolicyPackEvidenceForAnExplicitSingleMemberOverride() {
        Fixture fixture = Fixture.create();
        ResponsibilityAssignment owner = fixture.assignment(
                ResponsibilityRole.OWNER,
                fixture.owner,
                Optional.of(fixture.team.ownerMember()));
        ResponsibilityAssignment executor = fixture.assignment(
                ResponsibilityRole.EXECUTOR,
                fixture.owner,
                Optional.of(fixture.team.ownerMember()));
        ReviewerEligibilityPolicy policy = ReviewerEligibilityPolicy.withSingleMemberOverride(
                POLICY_PACK, "Emergency single-member maintenance");

        ReviewerEligibilityDecision decision = policy.evaluateGate(
                fixture.workItem,
                fixture.owner,
                fixture.team.ownerMember(),
                List.of(fixture.team.ownerMember()),
                List.of(owner, executor));

        assertEquals(ReviewerEligibilityMode.SINGLE_MEMBER_OVERRIDE, decision.mode());
        assertTrue(decision.degraded());
        assertEquals(Set.of(ResponsibilityRole.OWNER, ResponsibilityRole.EXECUTOR),
                decision.conflictingRoles());
        assertEquals(POLICY_PACK, decision.policyPack().orElseThrow());
        assertEquals(
                "Emergency single-member maintenance",
                decision.overrideReason().orElseThrow());
    }

    @Test
    void refusesTheSingleMemberOverrideWhenAnotherActiveMemberExists() {
        Fixture fixture = Fixture.create();
        Member availableReviewer = fixture.member("Available reviewer");
        ResponsibilityAssignment owner = fixture.assignment(
                ResponsibilityRole.OWNER,
                fixture.owner,
                Optional.of(fixture.team.ownerMember()));
        ReviewerEligibilityPolicy policy = ReviewerEligibilityPolicy.withSingleMemberOverride(
                POLICY_PACK, "Self review requested");

        ReviewerPolicyViolationException failure = assertThrows(
                ReviewerPolicyViolationException.class,
                () -> policy.evaluateGate(
                        fixture.workItem,
                        fixture.owner,
                        fixture.team.ownerMember(),
                        List.of(fixture.team.ownerMember(), availableReviewer.membership),
                        List.of(owner)));

        assertEquals("2", failure.error().details().get("activeMemberCount"));
        assertEquals("true", failure.error().details().get("singleMemberOverrideConfigured"));
    }

    @Test
    void doesNotRecordADegradationWhenTheConfiguredOverrideIsNotNeeded() {
        Fixture fixture = Fixture.create();
        ReviewerEligibilityPolicy policy = ReviewerEligibilityPolicy.withSingleMemberOverride(
                POLICY_PACK, "Available only when required");

        ReviewerEligibilityDecision decision = policy.evaluateGate(
                fixture.workItem,
                fixture.owner,
                fixture.team.ownerMember(),
                List.of(fixture.team.ownerMember()),
                List.of());

        assertEquals(ReviewerEligibilityMode.STRICT_SEPARATION, decision.mode());
        assertFalse(decision.degraded());
        assertTrue(decision.policyPack().isEmpty());
    }

    @Test
    void requiresTheMembershipQueryToContainTheActiveReviewer() {
        Fixture fixture = Fixture.create();
        Member reviewer = fixture.member("Reviewer");

        DomainValidationException failure = assertThrows(
                DomainValidationException.class,
                () -> ReviewerEligibilityPolicy.strict()
                        .evaluateGate(
                                fixture.workItem,
                                reviewer.principal,
                                reviewer.membership,
                                List.of(fixture.team.ownerMember()),
                                List.of()));

        assertEquals(
                "reviewerEligibilityPolicy.teamMembers",
                failure.error().details().get("field"));
    }

    @Test
    void validatesOverrideEvidenceAtConstruction() {
        assertThrows(
                DomainValidationException.class,
                () -> ReviewerEligibilityPolicy.withSingleMemberOverride(POLICY_PACK, " "));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PolicyPackReference(PolicyPackId.generate(), -1));
        assertThrows(
                DomainValidationException.class,
                () -> ReviewerEligibilityDecision.singleMemberOverride(
                        Set.of(), POLICY_PACK, "Missing conflict"));
    }

    private record Member(Principal principal, TeamMember membership) {}

    private static final class Fixture {

        private final OrganizationId organizationId;
        private final Principal owner;
        private final TeamInitialization team;
        private final WorkItem workItem;

        private Fixture(
                OrganizationId organizationId,
                Principal owner,
                TeamInitialization team,
                WorkItem workItem) {
            this.organizationId = organizationId;
            this.owner = owner;
            this.team = team;
            this.workItem = workItem;
        }

        private static Fixture create() {
            OrganizationId organizationId = OrganizationId.generate();
            Principal owner = activeUser(organizationId, "Owner");
            TeamInitialization team = TeamInitialization.create(owner, "Platform", NOW);
            WorkItem workItem = workItem(
                    organizationId,
                    team,
                    owner,
                    "CRW-1");
            return new Fixture(organizationId, owner, team, workItem);
        }

        private Member member(String name) {
            Principal principal = activeUser(organizationId, name);
            TeamMember membership = team.team().joinMember(
                    TeamMemberId.generate(), principal, TeamJoinMethod.OIDC, NOW);
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

        private ResponsibilityAssignment assignment(
                ResponsibilityRole role,
                Principal actor,
                Optional<TeamMember> actorMember) {
            return ResponsibilityAssignment.assign(
                    ResponsibilityAssignmentId.generate(),
                    workItem,
                    role,
                    actor,
                    actorMember,
                    owner,
                    NOW);
        }

        private WorkItem otherWorkItem() {
            return workItem(organizationId, team, owner, "CRW-2");
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

        private static WorkItem workItem(
                OrganizationId organizationId,
                TeamInitialization team,
                Principal owner,
                String key) {
            return WorkItem.create(
                    WorkItemId.generate(),
                    new WorkItemScope(
                            organizationId,
                            team.team().id(),
                            team.defaultWorkspace().id(),
                            WorkProjectId.generate()),
                    new WorkItemKey(key),
                    "Reviewer eligibility",
                    owner.id(),
                    NOW);
        }
    }
}
