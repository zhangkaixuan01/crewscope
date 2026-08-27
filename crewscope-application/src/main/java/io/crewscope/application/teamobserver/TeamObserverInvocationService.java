package io.crewscope.application.teamobserver;

import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamMemberRepository;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.teamobserver.TeamObserverInitialization;
import io.crewscope.domain.teamobserver.TeamSummaryEntry;
import io.crewscope.domain.teamobserver.TeamSummaryResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Member-authorized session, replay, cancellation and evidence boundary for Team Observer. */
public final class TeamObserverInvocationService {

    private static final int MAX_SESSIONS = 2_000;
    private static final int MAX_INVOCATIONS_PER_SESSION = 100;

    private final TeamRepository teams;
    private final TeamMemberRepository members;
    private final TeamObserverExecutionPort executions;
    private final TimeProvider timeProvider;
    private final Map<TeamObserverSessionId, SessionState> sessions = new LinkedHashMap<>();

    public TeamObserverInvocationService(
            TeamRepository teams,
            TeamMemberRepository members,
            TeamObserverExecutionPort executions,
            TimeProvider timeProvider) {
        this.teams = Objects.requireNonNull(teams, "teams");
        this.members = Objects.requireNonNull(members, "members");
        this.executions = Objects.requireNonNull(executions, "executions");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    public synchronized TeamObserverSession createSession(
            TeamAccessContext access, OrganizationId organizationId, TeamId teamId) {
        AuthorizedMember authorized = authorize(access, organizationId, teamId);
        if (sessions.size() >= MAX_SESSIONS) {
            evictOneTerminalSession();
        }
        if (sessions.size() >= MAX_SESSIONS) {
            throw new IllegalStateException("Team Observer session capacity exhausted");
        }
        TeamObserverSession session = new TeamObserverSession(
                TeamObserverSessionId.generate(),
                organizationId,
                teamId,
                authorized.member().id(),
                authorized.actor().id(),
                TeamObserverInitialization.stableProfileId(teamId),
                timeProvider.now());
        sessions.put(session.id(), new SessionState(session));
        return session;
    }

    public TeamObserverInvocationSegment invoke(
            TeamAccessContext access,
            OrganizationId organizationId,
            TeamId teamId,
            TeamObserverSessionId sessionId,
            String instruction,
            int maxItemsPerSection,
            UUID correlationId) {
        InvocationState invocation;
        TeamObserverExecution execution;
        synchronized (this) {
            SessionState session = requireSession(access, organizationId, teamId, sessionId);
            if (session.invocations.size() >= MAX_INVOCATIONS_PER_SESSION) {
                throw new IllegalStateException("Team Observer invocation capacity exhausted");
            }
            TeamObserverInvocationId invocationId = TeamObserverInvocationId.generate();
            invocation = new InvocationState(invocationId);
            session.invocations.put(invocationId, invocation);
            invocation.append(TeamObserverStreamEvent.Type.STARTED, Optional.empty(), Optional.empty());
            try {
                execution = executions.execute(new TeamObserverExecutionRequest(
                        organizationId,
                        teamId,
                        session.session.memberId(),
                        access.actor(),
                        sessionId,
                        invocationId,
                        instruction,
                        maxItemsPerSection,
                        correlationId));
                invocation.cancel = execution.cancel();
            } catch (RuntimeException failure) {
                invocation.terminal = true;
                invocation.append(
                        TeamObserverStreamEvent.Type.FAILED,
                        Optional.empty(),
                        Optional.of("team_observer_failed"));
                invocation.publisher.complete();
                return invocation.segment(sessionId, false);
            }
        }
        execution.result().whenComplete((summary, failure) -> complete(invocation, summary, failure));
        return invocation.segment(sessionId, false);
    }

    /** Replays current events and follows the same invocation; it never starts a second model call. */
    public synchronized TeamObserverInvocationSegment resume(
            TeamAccessContext access,
            OrganizationId organizationId,
            TeamId teamId,
            TeamObserverSessionId sessionId,
            TeamObserverInvocationId invocationId) {
        InvocationState invocation = requireInvocation(
                requireSession(access, organizationId, teamId, sessionId), invocationId);
        return invocation.segment(sessionId, true);
    }

    /** Revalidates membership and exact ownership immediately before an SSE frame is disclosed. */
    public synchronized void requireInvocationAccess(
            TeamAccessContext access,
            OrganizationId organizationId,
            TeamId teamId,
            TeamObserverSessionId sessionId,
            TeamObserverInvocationId invocationId) {
        requireInvocation(requireSession(access, organizationId, teamId, sessionId), invocationId);
    }

    public synchronized boolean cancel(
            TeamAccessContext access,
            OrganizationId organizationId,
            TeamId teamId,
            TeamObserverSessionId sessionId,
            TeamObserverInvocationId invocationId) {
        InvocationState invocation = requireInvocation(
                requireSession(access, organizationId, teamId, sessionId), invocationId);
        if (invocation.terminal) {
            return false;
        }
        invocation.terminal = true;
        invocation.cancel.run();
        invocation.append(TeamObserverStreamEvent.Type.CANCELLED, Optional.empty(), Optional.empty());
        invocation.publisher.complete();
        return true;
    }

    public synchronized TeamSummaryResult summary(
            TeamAccessContext access,
            OrganizationId organizationId,
            TeamId teamId,
            TeamObserverSessionId sessionId,
            TeamObserverInvocationId invocationId) {
        InvocationState invocation = requireInvocation(
                requireSession(access, organizationId, teamId, sessionId), invocationId);
        return invocation.summary.orElseThrow(() -> new DomainValidationException(
                "teamObserver.invocationId", "has no completed summary"));
    }

    public synchronized TeamObserverEvidenceLink evidence(
            TeamAccessContext access,
            OrganizationId organizationId,
            TeamId teamId,
            TeamObserverSessionId sessionId,
            TeamObserverInvocationId invocationId,
            int evidenceIndex) {
        TeamSummaryResult result = summary(
                access, organizationId, teamId, sessionId, invocationId);
        List<TeamSummaryEntry> entries = evidenceEntries(result);
        if (evidenceIndex < 0 || evidenceIndex >= entries.size()) {
            throw new DomainValidationException(
                    "teamObserver.evidenceIndex", "must identify selected summary evidence");
        }
        TeamSummaryEntry entry = entries.get(evidenceIndex);
        return new TeamObserverEvidenceLink(
                evidenceIndex,
                entry.section(),
                entry.dataScope(),
                entry.summary(),
                entry.evidencePath());
    }

    private synchronized void complete(
            InvocationState invocation, TeamSummaryResult summary, Throwable failure) {
        if (invocation.terminal) {
            return;
        }
        invocation.terminal = true;
        if (failure == null) {
            invocation.summary = Optional.of(Objects.requireNonNull(summary, "summary"));
            invocation.append(
                    TeamObserverStreamEvent.Type.SUMMARY_COMPLETED,
                    invocation.summary,
                    Optional.empty());
        } else {
            // Runtime and model details stay server-side; clients receive one stable failure code.
            invocation.append(
                    TeamObserverStreamEvent.Type.FAILED,
                    Optional.empty(),
                    Optional.of("team_observer_failed"));
        }
        invocation.publisher.complete();
    }

    private SessionState requireSession(
            TeamAccessContext access,
            OrganizationId organizationId,
            TeamId teamId,
            TeamObserverSessionId sessionId) {
        AuthorizedMember authorized = authorize(access, organizationId, teamId);
        SessionState state = Optional.ofNullable(sessions.get(sessionId))
                .orElseThrow(() -> new DomainValidationException(
                        "teamObserver.sessionId", "must identify a current member session"));
        TeamObserverSession session = state.session;
        if (!session.organizationId().equals(organizationId)
                || !session.teamId().equals(teamId)
                || !session.memberId().equals(authorized.member().id())
                || !session.actorId().equals(authorized.actor().id())) {
            throw new PolicyDeniedException("access this Team Observer session");
        }
        return state;
    }

    private AuthorizedMember authorize(
            TeamAccessContext access, OrganizationId organizationId, TeamId teamId) {
        Principal actor = Objects.requireNonNull(access, "access").actor();
        if (actor.type() != PrincipalType.USER
                || !actor.canAct()
                || !actor.scope().organizationId().equals(organizationId)) {
            throw new PolicyDeniedException("use Team Observer");
        }
        Team team = teams.findById(organizationId, teamId)
                .filter(Team::isActive)
                .orElseThrow(() -> new DomainValidationException(
                        "teamObserver.teamId", "must reference an active Team"));
        TeamMember member = members.findByTeamAndUserPrincipalId(
                        organizationId, teamId, actor.id())
                .filter(TeamMember::canParticipate)
                .filter(value -> value.userPrincipalId().equals(actor.id()))
                .filter(value -> value.scope().organizationId().equals(organizationId))
                .filter(value -> value.scope().teamId().equals(teamId))
                .orElseThrow(() -> new PolicyDeniedException("use Team Observer"));
        return new AuthorizedMember(actor, team, member);
    }

    private static InvocationState requireInvocation(
            SessionState session, TeamObserverInvocationId invocationId) {
        return Optional.ofNullable(session.invocations.get(invocationId))
                .orElseThrow(() -> new DomainValidationException(
                        "teamObserver.invocationId", "must identify this session's invocation"));
    }

    private static List<TeamSummaryEntry> evidenceEntries(TeamSummaryResult result) {
        List<TeamSummaryEntry> entries = new ArrayList<>();
        entries.addAll(result.progress());
        entries.addAll(result.blockers());
        entries.addAll(result.reviewBacklog());
        entries.addAll(result.pendingConfirmations());
        entries.addAll(result.anomalies());
        return List.copyOf(entries);
    }

    private void evictOneTerminalSession() {
        sessions.entrySet().stream()
                .filter(entry -> !entry.getValue().invocations.isEmpty())
                .filter(entry -> entry.getValue().invocations.values().stream()
                        .allMatch(value -> value.terminal))
                .findFirst()
                .ifPresent(entry -> sessions.remove(entry.getKey()));
    }

    private record AuthorizedMember(Principal actor, Team team, TeamMember member) {}

    private static final class SessionState {
        private final TeamObserverSession session;
        private final Map<TeamObserverInvocationId, InvocationState> invocations =
                new LinkedHashMap<>();

        private SessionState(TeamObserverSession session) {
            this.session = session;
        }
    }

    private final class InvocationState {
        private final TeamObserverInvocationId id;
        private final TeamObserverEventPublisher publisher = new TeamObserverEventPublisher();
        private long sequence;
        private Runnable cancel = () -> {};
        private Optional<TeamSummaryResult> summary = Optional.empty();
        private boolean terminal;

        private InvocationState(TeamObserverInvocationId id) {
            this.id = id;
        }

        private void append(
                TeamObserverStreamEvent.Type type,
                Optional<TeamSummaryResult> result,
                Optional<String> errorCode) {
            publisher.append(new TeamObserverStreamEvent(
                    id, ++sequence, timeProvider.now(), type, result, errorCode));
        }

        private TeamObserverInvocationSegment segment(
                TeamObserverSessionId sessionId, boolean resumed) {
            return new TeamObserverInvocationSegment(sessionId, id, publisher, resumed);
        }
    }
}
