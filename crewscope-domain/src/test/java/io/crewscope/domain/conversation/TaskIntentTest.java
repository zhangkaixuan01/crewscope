package io.crewscope.domain.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.workitem.WorkProject;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.domain.workitem.WorkProjectKey;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TaskIntentTest {

    private static final UtcTimestamp T2 = UtcTimestamp.parse("2026-08-08T12:02:00Z");
    private static final UtcTimestamp T3 = UtcTimestamp.parse("2026-08-08T12:03:00Z");

    @Test
    void createsDraftFromAgentWithResolvedProjectAndResponsibilities() {
        Context context = Context.create();
        TaskIntentProposal proposal = context.proposal(
                "  Deliver the conversation workflow  ",
                List.of("  Cursor history works  ", "Responsibilities are auditable"),
                Optional.of(TaskIntentCandidate.agent(context.agent())),
                Optional.of(context.reviewerCandidate()));

        TaskIntent intent = context.draft(proposal);

        assertEquals(TaskIntentStatus.DRAFT, intent.status());
        assertEquals(1, intent.schemaVersion());
        assertEquals(1, intent.proposalRevision());
        assertEquals("Deliver the conversation workflow", intent.proposal().objective());
        assertEquals("Cursor history works", intent.proposal().acceptanceCriteria().get(0));
        assertEquals(context.fixture.project.id(), intent.proposal().targetScope().projectId());
        assertEquals(context.fixture.owner.id(), intent.proposal().owner().principalId());
        assertEquals(context.agent().id(), intent.proposal().executor().orElseThrow().principalId());
        assertEquals(
                context.reviewer.id(),
                intent.proposal().gateReviewer().orElseThrow().principalId());
        assertEquals(context.agent().id(), intent.proposedByPrincipalId());
        assertEquals(context.agent().id(), intent.audit().createdBy().orElseThrow());
        assertTrue(intent.decision().isEmpty());
    }

    @Test
    void rejectsInvalidObjectiveCriteriaAndProjectScope() {
        Context context = Context.create();

        assertThrows(
                DomainValidationException.class,
                () -> context.proposal(
                        " ",
                        List.of("Valid"),
                        Optional.empty(),
                        Optional.empty()));
        assertThrows(
                DomainValidationException.class,
                () -> context.proposal(
                        "Valid",
                        List.of(),
                        Optional.empty(),
                        Optional.empty()));
        assertThrows(
                DomainValidationException.class,
                () -> context.proposal(
                        "Valid",
                        List.of("Same", "Same"),
                        Optional.empty(),
                        Optional.empty()));

        WorkProject archived = context.fixture.project.archive(
                context.fixture.owner, ConversationDomainFixture.LATER);
        assertThrows(
                DomainValidationException.class,
                () -> TaskIntentProposal.create(
                        context.fixture.conversation(),
                        archived,
                        "Valid",
                        List.of("Valid"),
                        context.ownerCandidate(),
                        Optional.empty(),
                        Optional.empty()));

        TeamInitialization otherTeam = TeamInitialization.create(
                context.fixture.owner, "Other", ConversationDomainFixture.CREATED_AT);
        WorkProject foreignProject = WorkProject.create(
                WorkProjectId.generate(),
                new WorkProjectKey("OTH"),
                "Other",
                otherTeam.team(),
                otherTeam.defaultWorkspace(),
                context.fixture.owner,
                ConversationDomainFixture.CREATED_AT);
        assertThrows(
                DomainValidationException.class,
                () -> TaskIntentProposal.create(
                        context.fixture.conversation(),
                        foreignProject,
                        "Valid",
                        List.of("Valid"),
                        context.ownerCandidate(),
                        Optional.empty(),
                        Optional.empty()));
    }

    @Test
    void requiresActiveQualifiedOwnerExecutorAndGateReviewer() {
        Context context = Context.create();
        TeamMember suspendedOwner = context.fixture.team.ownerMember().suspend(T2);

        assertThrows(
                DomainValidationException.class,
                () -> TaskIntentProposal.create(
                        context.fixture.conversation(),
                        context.fixture.project,
                        "Valid",
                        List.of("Valid"),
                        TaskIntentCandidate.user(context.fixture.owner, suspendedOwner),
                        Optional.empty(),
                        Optional.empty()));
        assertThrows(
                DomainValidationException.class,
                () -> TaskIntentProposal.create(
                        context.fixture.conversation(),
                        context.fixture.project,
                        "Valid",
                        List.of("Valid"),
                        TaskIntentCandidate.agent(context.agent()),
                        Optional.empty(),
                        Optional.empty()));
        assertThrows(
                DomainValidationException.class,
                () -> context.proposal(
                        "Valid",
                        List.of("Valid"),
                        Optional.of(new TaskIntentCandidate(
                                context.fixture.owner, Optional.empty())),
                        Optional.empty()));
        assertThrows(
                DomainValidationException.class,
                () -> context.proposal(
                        "Valid",
                        List.of("Valid"),
                        Optional.empty(),
                        Optional.of(TaskIntentCandidate.agent(context.agent()))));
    }

    @Test
    void keepsGateReviewerSeparatedFromOwnerAndExecutor() {
        Context context = Context.create();

        assertThrows(
                DomainValidationException.class,
                () -> context.proposal(
                        "Valid",
                        List.of("Valid"),
                        Optional.empty(),
                        Optional.of(context.ownerCandidate())));
        assertThrows(
                DomainValidationException.class,
                () -> context.proposal(
                        "Valid",
                        List.of("Valid"),
                        Optional.of(context.reviewerCandidate()),
                        Optional.of(context.reviewerCandidate())));

        TaskIntentProposal ownerAlsoExecutor = context.proposal(
                "Valid",
                List.of("Valid"),
                Optional.of(context.ownerCandidate()),
                Optional.of(context.reviewerCandidate()));
        assertEquals(
                ownerAlsoExecutor.owner().principalId(),
                ownerAlsoExecutor.executor().orElseThrow().principalId());
    }

    @Test
    void draftRequiresMatchingActiveAgentParticipantAndConversation() {
        Context context = Context.create();
        TaskIntentProposal proposal = context.proposal(
                "Valid", List.of("Valid"), Optional.empty(), Optional.empty());

        assertThrows(
                DomainValidationException.class,
                () -> TaskIntent.draft(
                        TaskIntentId.generate(),
                        context.fixture.conversation(),
                        context.fixture.initialization.ownerParticipant(),
                        context.fixture.owner,
                        proposal,
                        ConversationDomainFixture.CREATED_AT));
        Conversation archived = context.fixture.conversation().archive(
                context.fixture.owner, ConversationDomainFixture.LATER);
        assertThrows(
                DomainValidationException.class,
                () -> TaskIntent.draft(
                        TaskIntentId.generate(),
                        archived,
                        context.fixture.initialization.agentParticipant(),
                        context.agent(),
                        proposal,
                        T2));
    }

    @Test
    void movesDraftToReadyWithExpectedVersionAndAudit() {
        Context context = Context.create();
        TaskIntent draft = context.draft(context.defaultProposal());

        TaskIntent ready = draft.markReady(0, context.agent(), ConversationDomainFixture.LATER);

        assertEquals(TaskIntentStatus.READY, ready.status());
        assertEquals(1, ready.version());
        assertEquals(1, ready.proposalRevision());
        assertEquals(ConversationDomainFixture.LATER, ready.audit().updatedAt());
        assertEquals(context.agent().id(), ready.audit().updatedBy().orElseThrow());
        assertThrows(
                OptimisticLockConflictException.class,
                () -> draft.markReady(1, context.agent(), ConversationDomainFixture.LATER));
        assertThrows(
                InvalidStateTransitionException.class,
                () -> ready.markReady(1, context.agent(), T2));
    }

    @Test
    void revisionReturnsReadyIntentToDraftAndPreservesCreationFact() {
        Context context = Context.create();
        TaskIntent ready = context.draft(context.defaultProposal())
                .markReady(0, context.agent(), ConversationDomainFixture.LATER);
        TaskIntentProposal replacement = context.proposal(
                "Revised objective",
                List.of("Revised criterion"),
                Optional.of(TaskIntentCandidate.agent(context.agent())),
                Optional.of(context.reviewerCandidate()));

        TaskIntent revised = ready.revise(replacement, 1, context.fixture.owner, T2);

        assertEquals(TaskIntentStatus.DRAFT, revised.status());
        assertEquals(2, revised.proposalRevision());
        assertEquals(2, revised.version());
        assertEquals("Revised objective", revised.proposal().objective());
        assertEquals(ready.audit().createdAt(), revised.audit().createdAt());
        assertEquals(ready.proposedByPrincipalId(), revised.proposedByPrincipalId());
        assertThrows(
                DomainValidationException.class,
                () -> revised.revise(replacement, 2, context.fixture.owner, T3));
    }

    @Test
    void confirmsReadyIntentThroughProposedOwnerAfterCurrentFactValidation() {
        Context context = Context.create();
        TaskIntentProposal proposal = context.defaultProposal();
        TaskIntent ready = context.draft(proposal)
                .markReady(0, context.agent(), ConversationDomainFixture.LATER);

        TaskIntent confirmed = ready.confirm(1, proposal, context.fixture.owner, T2);

        assertEquals(TaskIntentStatus.CONFIRMED, confirmed.status());
        assertEquals(2, confirmed.version());
        assertEquals(context.fixture.owner.id(),
                confirmed.decision().orElseThrow().decidedByPrincipalId());
        assertTrue(confirmed.decision().orElseThrow().reason().isEmpty());
        assertEquals(T2, confirmed.audit().updatedAt());

        TaskIntentProposal changed = context.proposal(
                "Changed", List.of("Changed"), Optional.empty(), Optional.empty());
        assertThrows(
                DomainValidationException.class,
                () -> ready.confirm(1, changed, context.fixture.owner, T2));
        assertThrows(
                DomainValidationException.class,
                () -> ready.confirm(1, proposal, context.reviewer, T2));
    }

    @Test
    void rejectsOrExpiresEditableIntentWithReason() {
        Context context = Context.create();
        TaskIntent draft = context.draft(context.defaultProposal());
        TaskIntent rejected = draft.reject(0, context.fixture.owner, "  Wrong target  ", T2);
        TaskIntent ready = context.draft(context.defaultProposal())
                .markReady(0, context.agent(), ConversationDomainFixture.LATER);
        TaskIntent expired = ready.expire(
                1, context.fixture.owner, "Membership changed", T2);

        assertEquals(TaskIntentStatus.REJECTED, rejected.status());
        assertEquals("Wrong target", rejected.decision().orElseThrow().reason().orElseThrow());
        assertEquals(TaskIntentStatus.EXPIRED, expired.status());
        assertEquals(
                "Membership changed",
                expired.decision().orElseThrow().reason().orElseThrow());
        assertThrows(
                DomainValidationException.class,
                () -> draft.reject(0, context.fixture.owner, " ", T2));
    }

    @Test
    void terminalDecisionIsSingleAndCannotBeRevised() {
        Context context = Context.create();
        TaskIntent rejected = context.draft(context.defaultProposal())
                .reject(0, context.fixture.owner, "No", T2);

        assertThrows(
                InvalidStateTransitionException.class,
                () -> rejected.reject(1, context.fixture.owner, "Again", T3));
        assertThrows(
                InvalidStateTransitionException.class,
                () -> rejected.expire(1, context.fixture.owner, "Expired", T3));
        assertThrows(
                InvalidStateTransitionException.class,
                () -> rejected.revise(
                        context.proposal(
                                "Changed",
                                List.of("Changed"),
                                Optional.empty(),
                                Optional.empty()),
                        1,
                        context.fixture.owner,
                        T3));
    }

    @Test
    void allMutationsRejectStaleExpectedVersion() {
        Context context = Context.create();
        TaskIntent draft = context.draft(context.defaultProposal());
        TaskIntent ready = draft.markReady(0, context.agent(), ConversationDomainFixture.LATER);
        TaskIntentProposal replacement = context.proposal(
                "Changed", List.of("Changed"), Optional.empty(), Optional.empty());

        assertThrows(
                OptimisticLockConflictException.class,
                () -> draft.revise(replacement, 1, context.fixture.owner, T2));
        assertThrows(
                OptimisticLockConflictException.class,
                () -> draft.reject(1, context.fixture.owner, "No", T2));
        assertThrows(
                OptimisticLockConflictException.class,
                () -> draft.expire(1, context.fixture.owner, "Old", T2));
        assertThrows(
                OptimisticLockConflictException.class,
                () -> ready.confirm(0, ready.proposal(), context.fixture.owner, T2));
    }

    @Test
    void reconstitutionRejectsInvalidRevisionDecisionAndAudit() {
        Context context = Context.create();
        TaskIntent draft = context.draft(context.defaultProposal());
        TaskIntentDecision confirmed = TaskIntentDecision.confirmed(context.fixture.owner.id(), T2);

        assertThrows(
                DomainValidationException.class,
                () -> TaskIntent.reconstitute(
                        draft.id(),
                        draft.scope(),
                        draft.conversationId(),
                        draft.proposedByPrincipalId(),
                        0,
                        draft.proposal(),
                        TaskIntentStatus.DRAFT,
                        Optional.empty(),
                        0,
                        draft.audit()));
        assertThrows(
                DomainValidationException.class,
                () -> TaskIntent.reconstitute(
                        draft.id(),
                        draft.scope(),
                        draft.conversationId(),
                        draft.proposedByPrincipalId(),
                        1,
                        draft.proposal(),
                        TaskIntentStatus.CONFIRMED,
                        Optional.empty(),
                        1,
                        draft.audit()));
        assertThrows(
                DomainValidationException.class,
                () -> TaskIntent.reconstitute(
                        draft.id(),
                        draft.scope(),
                        draft.conversationId(),
                        draft.proposedByPrincipalId(),
                        1,
                        draft.proposal(),
                        TaskIntentStatus.CONFIRMED,
                        Optional.of(confirmed),
                        1,
                        AuditMetadata.createdBy(PrincipalId.generate(), T2)));
    }

    private record Context(
            ConversationDomainFixture fixture,
            Principal reviewer,
            TeamMember reviewerMember) {

        static Context create() {
            ConversationDomainFixture fixture = ConversationDomainFixture.create();
            Principal reviewer = ConversationDomainFixture.activeUser(
                    fixture.organizationId, "Reviewer");
            return new Context(fixture, reviewer, fixture.activeMember(reviewer));
        }

        Principal agent() {
            return fixture.team.ownerPersonalAgent().agentPrincipal();
        }

        TaskIntentCandidate ownerCandidate() {
            return TaskIntentCandidate.user(fixture.owner, fixture.team.ownerMember());
        }

        TaskIntentCandidate reviewerCandidate() {
            return TaskIntentCandidate.user(reviewer, reviewerMember);
        }

        TaskIntentProposal defaultProposal() {
            return proposal(
                    "Build M2-D03",
                    List.of("TaskIntent can be confirmed"),
                    Optional.of(TaskIntentCandidate.agent(agent())),
                    Optional.of(reviewerCandidate()));
        }

        TaskIntentProposal proposal(
                String objective,
                List<String> criteria,
                Optional<TaskIntentCandidate> executor,
                Optional<TaskIntentCandidate> gateReviewer) {
            return TaskIntentProposal.create(
                    fixture.conversation(),
                    fixture.project,
                    objective,
                    criteria,
                    ownerCandidate(),
                    executor,
                    gateReviewer);
        }

        TaskIntent draft(TaskIntentProposal proposal) {
            return TaskIntent.draft(
                    TaskIntentId.generate(),
                    fixture.conversation(),
                    fixture.initialization.agentParticipant(),
                    agent(),
                    proposal,
                    ConversationDomainFixture.CREATED_AT);
        }
    }
}
