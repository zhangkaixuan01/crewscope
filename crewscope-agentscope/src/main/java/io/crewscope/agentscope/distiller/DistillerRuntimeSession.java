package io.crewscope.agentscope.distiller;

import io.crewscope.domain.conversation.AgentRuntimeSessionId;
import io.crewscope.domain.conversation.AgentRuntimeStateReference;
import io.crewscope.domain.conversation.AgentScopeSessionKey;
import io.crewscope.domain.knowledge.distiller.KnowledgeDistillerInitialization;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.skill.distiller.SkillDistillerInitialization;
import io.crewscope.domain.workspace.AgentProfileId;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/**
 * Trusted initiating user and Team-bound AgentScope state slot for one Distiller call,
 * shared by the Knowledge and Skill distillers (M13-C02). The {@link DistillerKind} pins
 * the deterministic identity check and the state-slot namespaces; the coordinates below
 * are the server-derived trusted facts the runtime chain re-validates.
 */
public record DistillerRuntimeSession(
        DistillerKind kind,
        OrganizationId organizationId,
        TeamId teamId,
        PrincipalId initiatedBy,
        PrincipalId distillerPrincipalId,
        AgentProfileId distillerProfileId,
        long distillerProfileVersion,
        UUID distillationSessionId) {

    public DistillerRuntimeSession {
        kind = Objects.requireNonNull(kind, "kind");
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        initiatedBy = Objects.requireNonNull(initiatedBy, "initiatedBy");
        distillerPrincipalId = Objects.requireNonNull(distillerPrincipalId, "distillerPrincipalId");
        distillerProfileId = Objects.requireNonNull(distillerProfileId, "distillerProfileId");
        distillationSessionId = Objects.requireNonNull(
                distillationSessionId, "distillationSessionId");
        if (distillerProfileVersion < 0
                || !distillerPrincipalId.equals(stablePrincipalId(kind, teamId))
                || !distillerProfileId.equals(stableProfileId(kind, teamId))) {
            throw new IllegalArgumentException(kind.label()
                    + " Session must use the Team's deterministic Distiller identity");
        }
    }

    /** The Team's deterministic Distiller principal for the given kind. */
    public static PrincipalId stablePrincipalId(DistillerKind kind, TeamId teamId) {
        return switch (kind) {
            case KNOWLEDGE -> KnowledgeDistillerInitialization.stablePrincipalId(teamId);
            case SKILL -> SkillDistillerInitialization.stablePrincipalId(teamId);
        };
    }

    /** The Team's deterministic Distiller profile for the given kind. */
    public static AgentProfileId stableProfileId(DistillerKind kind, TeamId teamId) {
        return switch (kind) {
            case KNOWLEDGE -> KnowledgeDistillerInitialization.stableProfileId(teamId);
            case SKILL -> SkillDistillerInitialization.stableProfileId(teamId);
        };
    }

    /** State keys include Organization, Team, initiator, Distiller and per-command session ID. */
    public AgentScopeSessionKey agentScopeKey() {
        return new AgentScopeSessionKey(
                "crewscope:v1:user:" + organizationId + ":" + teamId + ":"
                        + initiatedBy + ":" + distillerPrincipalId,
                "crewscope:v1:session:" + kind.sessionNamespace() + ":" + teamId + ":"
                        + distillationSessionId);
    }

    public AgentRuntimeStateReference stateReference() {
        return AgentRuntimeStateReference.forSession(
                new AgentRuntimeSessionId(UUID.nameUUIDFromBytes((
                                kind.stateTopicPrefix()
                                        + organizationId + "/" + teamId + "/"
                                        + initiatedBy + "/" + distillerProfileId + "/"
                                        + distillationSessionId)
                        .getBytes(StandardCharsets.UTF_8))));
    }
}
