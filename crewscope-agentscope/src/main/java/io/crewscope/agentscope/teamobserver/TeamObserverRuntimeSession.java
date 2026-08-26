package io.crewscope.agentscope.teamobserver;

import io.crewscope.domain.conversation.AgentRuntimeSessionId;
import io.crewscope.domain.conversation.AgentRuntimeStateReference;
import io.crewscope.domain.conversation.AgentScopeSessionKey;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.teamobserver.TeamObserverInitialization;
import io.crewscope.domain.workspace.AgentProfileId;
import java.util.Objects;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

/** Trusted member and Team-bound AgentScope state slot for one Observer conversation. */
public record TeamObserverRuntimeSession(
        OrganizationId organizationId,
        TeamId teamId,
        TeamMemberId requestingMemberId,
        PrincipalId observerPrincipalId,
        AgentProfileId observerProfileId,
        long observerProfileVersion,
        UUID conversationSessionId) {

    public TeamObserverRuntimeSession {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        requestingMemberId = Objects.requireNonNull(requestingMemberId, "requestingMemberId");
        observerPrincipalId = Objects.requireNonNull(observerPrincipalId, "observerPrincipalId");
        observerProfileId = Objects.requireNonNull(observerProfileId, "observerProfileId");
        conversationSessionId = Objects.requireNonNull(
                conversationSessionId, "conversationSessionId");
        if (observerProfileVersion < 0
                || !observerPrincipalId.equals(TeamObserverInitialization.stablePrincipalId(teamId))
                || !observerProfileId.equals(TeamObserverInitialization.stableProfileId(teamId))) {
            throw new IllegalArgumentException(
                    "Team Observer Session must use the Team's deterministic active Observer identity");
        }
    }

    /** State keys include Organization, Team, member, Observer and server-issued conversation ID. */
    public AgentScopeSessionKey agentScopeKey() {
        return new AgentScopeSessionKey(
                "crewscope:v1:user:" + organizationId + ":" + teamId + ":"
                        + requestingMemberId + ":" + observerPrincipalId,
                "crewscope:v1:session:team-observer:" + teamId + ":" + conversationSessionId);
    }

    public AgentRuntimeStateReference stateReference() {
        return AgentRuntimeStateReference.forSession(
                new AgentRuntimeSessionId(UUID.nameUUIDFromBytes((
                                "io.crewscope/team-observer-state/v1/"
                                        + organizationId + "/" + teamId + "/"
                                        + requestingMemberId + "/" + observerProfileId + "/"
                                        + conversationSessionId)
                        .getBytes(StandardCharsets.UTF_8))));
    }
}
