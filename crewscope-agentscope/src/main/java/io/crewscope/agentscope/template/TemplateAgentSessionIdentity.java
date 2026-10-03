package io.crewscope.agentscope.template;

import io.crewscope.agentscope.knowledge.KnowledgeDistillerRuntimeSession;
import io.crewscope.agentscope.teamobserver.TeamObserverRuntimeSession;
import io.crewscope.domain.conversation.AgentRuntimeSession;
import io.crewscope.domain.conversation.AgentRuntimeStateReference;
import io.crewscope.domain.conversation.AgentScopeSessionKey;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.task.TaskAgentRuntimeSession;
import io.crewscope.domain.task.TaskAgentSessionPurpose;
import io.crewscope.domain.workspace.AgentProfileId;
import java.util.Objects;
import java.util.Optional;

/** Trusted Principal, Profile, Session and state slot selected for one Template Agent call. */
public final class TemplateAgentSessionIdentity {

    public enum Kind {
        CONVERSATION,
        TASK,
        REVIEW,
        TEAM_OBSERVER,
        DISTILLER
    }

    private final Kind kind;
    private final PrincipalId agentPrincipalId;
    private final AgentProfileId agentProfileId;
    private final long agentProfileVersion;
    private final AgentScopeSessionKey agentScopeKey;
    private final AgentRuntimeStateReference stateReference;
    private final Optional<TaskAgentRuntimeSession> taskSession;

    private TemplateAgentSessionIdentity(
            Kind kind,
            PrincipalId agentPrincipalId,
            AgentProfileId agentProfileId,
            long agentProfileVersion,
            AgentScopeSessionKey agentScopeKey,
            AgentRuntimeStateReference stateReference,
            Optional<TaskAgentRuntimeSession> taskSession) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.agentPrincipalId = Objects.requireNonNull(agentPrincipalId, "agentPrincipalId");
        this.agentProfileId = Objects.requireNonNull(agentProfileId, "agentProfileId");
        if (agentProfileVersion < 0) {
            throw new IllegalArgumentException("agentProfileVersion must not be negative");
        }
        this.agentProfileVersion = agentProfileVersion;
        this.agentScopeKey = Objects.requireNonNull(agentScopeKey, "agentScopeKey");
        this.stateReference = Objects.requireNonNull(stateReference, "stateReference");
        this.taskSession = Objects.requireNonNull(taskSession, "taskSession");
    }

    public static TemplateAgentSessionIdentity conversation(AgentRuntimeSession session) {
        AgentRuntimeSession required = Objects.requireNonNull(session, "session");
        if (!required.canInvoke()) {
            throw new IllegalArgumentException("Conversation Agent Session must be active");
        }
        return new TemplateAgentSessionIdentity(
                Kind.CONVERSATION,
                required.personalAgentPrincipalId(),
                required.agentProfileId(),
                required.agentProfileVersion(),
                required.agentScopeKey(),
                required.stateReference(),
                Optional.empty());
    }

    public static TemplateAgentSessionIdentity task(TaskAgentRuntimeSession session) {
        TaskAgentRuntimeSession required = Objects.requireNonNull(session, "session");
        if (!required.canInvoke()) {
            throw new IllegalArgumentException("Task Agent Session must be active");
        }
        return new TemplateAgentSessionIdentity(
                Kind.TASK,
                required.agentPrincipalId(),
                required.agentProfileId(),
                required.agentProfileVersion(),
                required.agentScopeKey(),
                required.stateReference(),
                Optional.of(required));
    }

    /**
     * The advisory reviewer's own trusted kind: step-less like a TASK session but with an
     * independent, conversation-less security chain. Routing it through TASK would select the
     * conversation-semantics platform chain, which a review call can never satisfy (defect 20,
     * M9b-Q02) — the Kind selects the security chain, so REVIEW must be its own value.
     */
    public static TemplateAgentSessionIdentity review(TaskAgentRuntimeSession session) {
        TaskAgentRuntimeSession required = Objects.requireNonNull(session, "session");
        if (!required.canInvoke()) {
            throw new IllegalArgumentException("Review Agent Session must be active");
        }
        if (required.purpose() != TaskAgentSessionPurpose.REVIEW) {
            throw new IllegalArgumentException(
                    "Review Agent Session must carry the REVIEW purpose");
        }
        return new TemplateAgentSessionIdentity(
                Kind.REVIEW,
                required.agentPrincipalId(),
                required.agentProfileId(),
                required.agentProfileVersion(),
                required.agentScopeKey(),
                required.stateReference(),
                Optional.of(required));
    }

    /** Creates an identity from the server-derived Team/member Observer state coordinates. */
    public static TemplateAgentSessionIdentity teamObserver(TeamObserverRuntimeSession session) {
        TeamObserverRuntimeSession required = Objects.requireNonNull(session, "session");
        return new TemplateAgentSessionIdentity(
                Kind.TEAM_OBSERVER,
                required.observerPrincipalId(),
                required.observerProfileId(),
                required.observerProfileVersion(),
                required.agentScopeKey(),
                required.stateReference(),
                Optional.empty());
    }

    /**
     * The built-in Knowledge Distiller's own trusted kind: like TEAM_OBSERVER it owns a
     * conversation-less, tool-less boundary, so it selects its own security chain rather
     * than the conversation-semantics platform chain.
     */
    public static TemplateAgentSessionIdentity distiller(KnowledgeDistillerRuntimeSession session) {
        KnowledgeDistillerRuntimeSession required = Objects.requireNonNull(session, "session");
        return new TemplateAgentSessionIdentity(
                Kind.DISTILLER,
                required.distillerPrincipalId(),
                required.distillerProfileId(),
                required.distillerProfileVersion(),
                required.agentScopeKey(),
                required.stateReference(),
                Optional.empty());
    }

    public void requireDefinition(AgentTemplateRuntimeDefinition definition) {
        AgentTemplateRuntimeDefinition required = Objects.requireNonNull(definition, "definition");
        if (!agentProfileId.equals(required.profile().id())
                || agentProfileVersion != required.profile().version()
                || !agentPrincipalId.equals(required.profile().agentPrincipalId())) {
            throw new IllegalArgumentException(
                    "Agent Session must match the exact Template Runtime Profile and Principal");
        }
    }

    public void requireTaskPurpose(TaskAgentSessionPurpose purpose) {
        if (taskSession.map(TaskAgentRuntimeSession::purpose).filter(purpose::equals).isEmpty()) {
            throw new IllegalArgumentException("Task Agent Session purpose does not match the Factory");
        }
    }

    /** The Specialist factory serves both step-execution sessions and advisory review sessions. */
    public void requireSpecialistRuntimePurpose() {
        if (taskSession.map(TaskAgentRuntimeSession::purpose)
                .filter(purpose -> purpose == TaskAgentSessionPurpose.SPECIALIST
                        || purpose == TaskAgentSessionPurpose.REVIEW)
                .isEmpty()) {
            throw new IllegalArgumentException("Task Agent Session purpose does not match the Factory");
        }
    }

    public Kind kind() {
        return kind;
    }

    public PrincipalId agentPrincipalId() {
        return agentPrincipalId;
    }

    public AgentProfileId agentProfileId() {
        return agentProfileId;
    }

    public long agentProfileVersion() {
        return agentProfileVersion;
    }

    public AgentScopeSessionKey agentScopeKey() {
        return agentScopeKey;
    }

    public AgentRuntimeStateReference stateReference() {
        return stateReference;
    }

    public TaskAgentRuntimeSession requireTaskSession() {
        return taskSession.orElseThrow(() ->
                new IllegalArgumentException("A Task Agent Session is required"));
    }
}
