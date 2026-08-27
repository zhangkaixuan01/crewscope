package io.crewscope.application.teamobserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamMemberRepository;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.teamobserver.TeamSummaryDataScope;
import io.crewscope.domain.teamobserver.TeamSummaryEntry;
import io.crewscope.domain.teamobserver.TeamSummaryResult;
import io.crewscope.domain.teamobserver.TeamSummarySection;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Session isolation, resumable stream, explicit cancel and evidence reauthorization for M6-A05. */
class TeamObserverInvocationServiceM6A05Test {

    private static final OrganizationId ORGANIZATION_ID = OrganizationId.generate();
    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-27T04:00:00Z");

    private Principal actor;
    private TeamInitialization initialized;
    private TeamRepository teams;
    private TeamMemberRepository members;
    private CompletableFuture<TeamSummaryResult> runtimeResult;
    private Runnable cancel;
    private TeamObserverInvocationService service;
    private TeamAccessContext access;

    @BeforeEach
    void setUp() {
        actor = Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(ORGANIZATION_ID),
                PrincipalType.USER,
                Optional.empty(),
                "Owner",
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                NOW);
        initialized = TeamInitialization.create(actor, "Platform", NOW);
        teams = mock(TeamRepository.class);
        members = mock(TeamMemberRepository.class);
        when(teams.findById(ORGANIZATION_ID, initialized.team().id()))
                .thenReturn(Optional.of(initialized.team()));
        when(members.findByTeamAndUserPrincipalId(
                        ORGANIZATION_ID, initialized.team().id(), actor.id()))
                .thenReturn(Optional.of(initialized.ownerMember()));
        runtimeResult = new CompletableFuture<>();
        cancel = mock(Runnable.class);
        TeamObserverExecutionPort executions = request ->
                new TeamObserverExecution(runtimeResult, cancel);
        service = new TeamObserverInvocationService(teams, members, executions, () -> NOW);
        access = new TeamAccessContext(actor, false);
    }

    @Test
    void resumeReplaysTheSameInvocationWithoutStartingAnotherExecution() {
        TeamObserverSession session = session();
        TeamObserverInvocationSegment first = service.invoke(
                access,
                ORGANIZATION_ID,
                initialized.team().id(),
                session.id(),
                "Summarize current delivery risk",
                5,
                UUID.randomUUID());
        EventCollector initialEvents = new EventCollector();
        first.events().subscribe(initialEvents);
        assertEquals(TeamObserverStreamEvent.Type.STARTED, initialEvents.events.get(0).type());

        TeamObserverInvocationSegment resumed = service.resume(
                access,
                ORGANIZATION_ID,
                initialized.team().id(),
                session.id(),
                first.invocationId());
        assertEquals(first.invocationId(), resumed.invocationId());
        assertTrue(resumed.resumed());

        TeamSummaryResult summary = summary();
        runtimeResult.complete(summary);
        EventCollector resumedEvents = new EventCollector();
        resumed.events().subscribe(resumedEvents);
        assertEquals(
                List.of(
                        TeamObserverStreamEvent.Type.STARTED,
                        TeamObserverStreamEvent.Type.SUMMARY_COMPLETED),
                resumedEvents.events.stream().map(TeamObserverStreamEvent::type).toList());
        assertEquals(summary, service.summary(
                access,
                ORGANIZATION_ID,
                initialized.team().id(),
                session.id(),
                first.invocationId()));
    }

    @Test
    void explicitCancelIsIdempotentAndIndependentFromSubscriberDisconnect() {
        TeamObserverSession session = session();
        TeamObserverInvocationSegment invocation = invoke(session);
        EventCollector disconnected = new EventCollector();
        invocation.events().subscribe(disconnected);
        disconnected.subscription.cancel();
        verify(cancel, org.mockito.Mockito.never()).run();

        assertTrue(service.cancel(
                access,
                ORGANIZATION_ID,
                initialized.team().id(),
                session.id(),
                invocation.invocationId()));
        assertFalse(service.cancel(
                access,
                ORGANIZATION_ID,
                initialized.team().id(),
                session.id(),
                invocation.invocationId()));
        verify(cancel).run();
    }

    @Test
    void everyResumeAndEvidenceOpenRequiresCurrentMembership() {
        TeamObserverSession session = session();
        TeamObserverInvocationSegment invocation = invoke(session);
        runtimeResult.complete(summary());
        when(members.findByTeamAndUserPrincipalId(
                        ORGANIZATION_ID, initialized.team().id(), actor.id()))
                .thenReturn(Optional.empty());

        assertThrows(PolicyDeniedException.class, () -> service.resume(
                access,
                ORGANIZATION_ID,
                initialized.team().id(),
                session.id(),
                invocation.invocationId()));
        assertThrows(PolicyDeniedException.class, () -> service.evidence(
                access,
                ORGANIZATION_ID,
                initialized.team().id(),
                session.id(),
                invocation.invocationId(),
                0));
    }

    @Test
    void sessionCannotCrossTeamOrPrincipalAndEvidenceMustHaveBeenSelected() {
        TeamObserverSession session = session();
        TeamObserverInvocationSegment invocation = invoke(session);
        runtimeResult.complete(summary());
        assertEquals("/api/v1/evidence/1", service.evidence(
                        access,
                        ORGANIZATION_ID,
                        initialized.team().id(),
                        session.id(),
                        invocation.invocationId(),
                        0)
                .path());
        assertThrows(RuntimeException.class, () -> service.evidence(
                access,
                ORGANIZATION_ID,
                initialized.team().id(),
                session.id(),
                invocation.invocationId(),
                1));

        Principal other = Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(ORGANIZATION_ID),
                PrincipalType.USER,
                Optional.empty(),
                "Other",
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                NOW);
        when(members.findByTeamAndUserPrincipalId(
                        ORGANIZATION_ID, initialized.team().id(), other.id()))
                .thenReturn(Optional.of(initialized.ownerMember()));
        assertThrows(PolicyDeniedException.class, () -> service.resume(
                new TeamAccessContext(other, false),
                ORGANIZATION_ID,
                initialized.team().id(),
                session.id(),
                invocation.invocationId()));
    }

    private TeamObserverSession session() {
        return service.createSession(access, ORGANIZATION_ID, initialized.team().id());
    }

    private TeamObserverInvocationSegment invoke(TeamObserverSession session) {
        return service.invoke(
                access,
                ORGANIZATION_ID,
                initialized.team().id(),
                session.id(),
                "Summarize the Team",
                5,
                UUID.randomUUID());
    }

    private TeamSummaryResult summary() {
        TeamSummaryResult summary = mock(TeamSummaryResult.class);
        TeamSummaryEntry entry = new TeamSummaryEntry(
                ORGANIZATION_ID,
                initialized.team().id(),
                initialized.ownerMember().id(),
                TeamSummarySection.PROGRESS,
                TeamSummaryDataScope.TEAM_ACTIVITY,
                "Delivery is progressing",
                "/api/v1/evidence/1");
        when(summary.progress()).thenReturn(List.of(entry));
        when(summary.blockers()).thenReturn(List.of());
        when(summary.reviewBacklog()).thenReturn(List.of());
        when(summary.pendingConfirmations()).thenReturn(List.of());
        when(summary.anomalies()).thenReturn(List.of());
        return summary;
    }

    private static final class EventCollector implements Flow.Subscriber<TeamObserverStreamEvent> {

        private final List<TeamObserverStreamEvent> events = new ArrayList<>();
        private Flow.Subscription subscription;

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(TeamObserverStreamEvent item) {
            events.add(item);
        }

        @Override
        public void onError(Throwable throwable) {
            throw new AssertionError(throwable);
        }

        @Override
        public void onComplete() {}
    }
}
