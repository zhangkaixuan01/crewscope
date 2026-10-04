package io.crewscope.agentscope.skill;

import io.crewscope.domain.conversation.AgentRuntimeSessionId;
import io.crewscope.domain.conversation.AgentRuntimeStateReference;
import io.crewscope.domain.conversation.AgentScopeSessionKey;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.skill.distiller.SkillDistillerInitialization;
import io.crewscope.domain.workspace.AgentProfileId;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** Trusted initiating user and Team-bound AgentScope state slot for one Skill Distiller call. */
public record SkillDistillerRuntimeSession(
        OrganizationId organizationId,
        TeamId teamId,
        PrincipalId initiatedBy,
        PrincipalId distillerPrincipalId,
        AgentProfileId distillerProfileId,
        long distillerProfileVersion,
        UUID distillationSessionId) {

    public SkillDistillerRuntimeSession {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        initiatedBy = Objects.requireNonNull(initiatedBy, "initiatedBy");
        distillerPrincipalId = Objects.requireNonNull(distillerPrincipalId, "distillerPrincipalId");
        distillerProfileId = Objects.requireNonNull(distillerProfileId, "distillerProfileId");
        distillationSessionId = Objects.requireNonNull(
                distillationSessionId, "distillationSessionId");
        if (distillerProfileVersion < 0
                || !distillerPrincipalId.equals(
                        SkillDistillerInitialization.stablePrincipalId(teamId))
                || !distillerProfileId.equals(
                        SkillDistillerInitialization.stableProfileId(teamId))) {
            throw new IllegalArgumentException(
                    "Skill Distiller Session must use the Team's deterministic Distiller identity");
        }
    }

    /** State keys include Organization, Team, initiator, Distiller and per-command session ID. */
    public AgentScopeSessionKey agentScopeKey() {
        return new AgentScopeSessionKey(
                "crewscope:v1:user:" + organizationId + ":" + teamId + ":"
                        + initiatedBy + ":" + distillerPrincipalId,
                "crewscope:v1:session:skill-distiller:" + teamId + ":" + distillationSessionId);
    }

    public AgentRuntimeStateReference stateReference() {
        return AgentRuntimeStateReference.forSession(
                new AgentRuntimeSessionId(UUID.nameUUIDFromBytes((
                                "io.crewscope/skill-distiller-state/v1/"
                                        + organizationId + "/" + teamId + "/"
                                        + initiatedBy + "/" + distillerProfileId + "/"
                                        + distillationSessionId)
                        .getBytes(StandardCharsets.UTF_8))));
    }
}
