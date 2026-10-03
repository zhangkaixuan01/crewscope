package io.crewscope.agentscope.knowledge;

import io.crewscope.domain.conversation.AgentRuntimeSessionId;
import io.crewscope.domain.conversation.AgentRuntimeStateReference;
import io.crewscope.domain.conversation.AgentScopeSessionKey;
import io.crewscope.domain.knowledge.distiller.KnowledgeDistillerInitialization;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.workspace.AgentProfileId;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** Trusted initiating user and Team-bound AgentScope state slot for one Distiller call. */
public record KnowledgeDistillerRuntimeSession(
        OrganizationId organizationId,
        TeamId teamId,
        PrincipalId initiatedBy,
        PrincipalId distillerPrincipalId,
        AgentProfileId distillerProfileId,
        long distillerProfileVersion,
        UUID distillationSessionId) {

    public KnowledgeDistillerRuntimeSession {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        initiatedBy = Objects.requireNonNull(initiatedBy, "initiatedBy");
        distillerPrincipalId = Objects.requireNonNull(distillerPrincipalId, "distillerPrincipalId");
        distillerProfileId = Objects.requireNonNull(distillerProfileId, "distillerProfileId");
        distillationSessionId = Objects.requireNonNull(
                distillationSessionId, "distillationSessionId");
        if (distillerProfileVersion < 0
                || !distillerPrincipalId.equals(
                        KnowledgeDistillerInitialization.stablePrincipalId(teamId))
                || !distillerProfileId.equals(
                        KnowledgeDistillerInitialization.stableProfileId(teamId))) {
            throw new IllegalArgumentException(
                    "Knowledge Distiller Session must use the Team's deterministic Distiller identity");
        }
    }

    /** State keys include Organization, Team, initiator, Distiller and per-command session ID. */
    public AgentScopeSessionKey agentScopeKey() {
        return new AgentScopeSessionKey(
                "crewscope:v1:user:" + organizationId + ":" + teamId + ":"
                        + initiatedBy + ":" + distillerPrincipalId,
                "crewscope:v1:session:knowledge-distiller:" + teamId + ":" + distillationSessionId);
    }

    public AgentRuntimeStateReference stateReference() {
        return AgentRuntimeStateReference.forSession(
                new AgentRuntimeSessionId(UUID.nameUUIDFromBytes((
                                "io.crewscope/knowledge-distiller-state/v1/"
                                        + organizationId + "/" + teamId + "/"
                                        + initiatedBy + "/" + distillerProfileId + "/"
                                        + distillationSessionId)
                        .getBytes(StandardCharsets.UTF_8))));
    }
}
