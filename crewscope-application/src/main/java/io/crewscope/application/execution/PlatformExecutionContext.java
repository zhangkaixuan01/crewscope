package io.crewscope.application.execution;

import io.crewscope.domain.conversation.AgentRuntimeSession;
import io.crewscope.domain.conversation.AgentRuntimeSessionId;
import io.crewscope.domain.conversation.AgentScopeSessionKey;
import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.conversation.ConversationParticipantId;
import io.crewscope.domain.conversation.ConversationScope;
import io.crewscope.domain.conversation.ConversationVisibility;
import io.crewscope.domain.provider.ProviderType;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.team.TeamPermission;
import io.crewscope.domain.team.TeamRoleKey;
import io.crewscope.domain.workspace.AgentProfileId;
import io.crewscope.domain.workspace.WorkspaceType;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Immutable server-resolved authorization facts for exactly one AgentScope call. */
public record PlatformExecutionContext(
        ConversationScope scope,
        WorkspaceType workspaceType,
        PrincipalId requestPrincipalId,
        TeamMemberId teamMemberId,
        Set<TeamRoleKey> teamRoleKeys,
        Set<TeamPermission> teamPermissions,
        PrincipalId personalAgentPrincipalId,
        AgentProfileId agentProfileId,
        long agentProfileVersion,
        ConversationId conversationId,
        ConversationVisibility conversationVisibility,
        ConversationParticipantId userParticipantId,
        ConversationParticipantId agentParticipantId,
        AgentRuntimeSessionId runtimeSessionId,
        AgentScopeSessionKey agentScopeSessionKey,
        RuntimeInvocationId invocationId,
        UUID correlationId,
        Set<ProviderType> requiredProviderTypes,
        Map<ProviderType, ResolvedProviderBinding> providerBindings) {

    public PlatformExecutionContext {
        scope = Objects.requireNonNull(scope, "scope");
        workspaceType = Objects.requireNonNull(workspaceType, "workspaceType");
        requestPrincipalId = Objects.requireNonNull(requestPrincipalId, "requestPrincipalId");
        teamMemberId = Objects.requireNonNull(teamMemberId, "teamMemberId");
        teamRoleKeys = Set.copyOf(Objects.requireNonNull(teamRoleKeys, "teamRoleKeys"));
        teamPermissions = Set.copyOf(Objects.requireNonNull(teamPermissions, "teamPermissions"));
        personalAgentPrincipalId = Objects.requireNonNull(
                personalAgentPrincipalId, "personalAgentPrincipalId");
        agentProfileId = Objects.requireNonNull(agentProfileId, "agentProfileId");
        conversationId = Objects.requireNonNull(conversationId, "conversationId");
        conversationVisibility = Objects.requireNonNull(
                conversationVisibility, "conversationVisibility");
        userParticipantId = Objects.requireNonNull(userParticipantId, "userParticipantId");
        agentParticipantId = Objects.requireNonNull(agentParticipantId, "agentParticipantId");
        runtimeSessionId = Objects.requireNonNull(runtimeSessionId, "runtimeSessionId");
        agentScopeSessionKey = Objects.requireNonNull(
                agentScopeSessionKey, "agentScopeSessionKey");
        invocationId = Objects.requireNonNull(invocationId, "invocationId");
        correlationId = requireUuid(correlationId, "correlationId");
        requiredProviderTypes = Set.copyOf(Objects.requireNonNull(
                requiredProviderTypes, "requiredProviderTypes"));
        EnumMap<ProviderType, ResolvedProviderBinding> safeBindings =
                new EnumMap<>(ProviderType.class);
        Objects.requireNonNull(providerBindings, "providerBindings").forEach((type, binding) -> {
            ProviderType requiredType = Objects.requireNonNull(type, "providerBindings key");
            ResolvedProviderBinding requiredBinding = Objects.requireNonNull(
                    binding, "providerBindings value");
            if (requiredBinding.providerType() != requiredType) {
                throw new IllegalArgumentException(
                        "providerBindings key must match the Binding ProviderType");
            }
            safeBindings.put(requiredType, requiredBinding);
        });
        providerBindings = Map.copyOf(safeBindings);
        if (agentProfileVersion < 0) {
            throw new IllegalArgumentException("agentProfileVersion must not be negative");
        }
    }

    /** Verifies that a call request and its durable Session carry the same trusted identity. */
    public void requireMatches(
            AgentRuntimeSession session,
            RuntimeInvocationId expectedInvocationId,
            UUID expectedCorrelationId) {
        AgentRuntimeSession requiredSession = Objects.requireNonNull(session, "runtimeSession");
        if (!scope.equals(requiredSession.scope())
                || !conversationId.equals(requiredSession.conversationId())
                || !teamMemberId.equals(requiredSession.ownerMemberId())
                || !requestPrincipalId.equals(requiredSession.ownerPrincipalId())
                || !personalAgentPrincipalId.equals(requiredSession.personalAgentPrincipalId())
                || !agentProfileId.equals(requiredSession.agentProfileId())
                || agentProfileVersion != requiredSession.agentProfileVersion()
                || !runtimeSessionId.equals(requiredSession.id())
                || !agentScopeSessionKey.equals(requiredSession.agentScopeKey())
                || !invocationId.equals(Objects.requireNonNull(
                        expectedInvocationId, "expectedInvocationId"))
                || !correlationId.equals(Objects.requireNonNull(
                        expectedCorrelationId, "expectedCorrelationId"))) {
            throw new IllegalArgumentException(
                    "PlatformExecutionContext does not match the trusted execution request");
        }
    }

    public boolean hasAllRequiredProviderBindings() {
        return providerBindings.keySet().containsAll(requiredProviderTypes);
    }

    private static UUID requireUuid(UUID value, String field) {
        UUID required = Objects.requireNonNull(value, field);
        if (required.getMostSignificantBits() == 0L && required.getLeastSignificantBits() == 0L) {
            throw new IllegalArgumentException(field + " must not use the nil UUID");
        }
        return required;
    }
}
