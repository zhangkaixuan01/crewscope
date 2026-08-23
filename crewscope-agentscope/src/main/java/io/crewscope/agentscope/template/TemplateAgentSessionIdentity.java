package io.crewscope.agentscope.template;

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
        TASK
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
