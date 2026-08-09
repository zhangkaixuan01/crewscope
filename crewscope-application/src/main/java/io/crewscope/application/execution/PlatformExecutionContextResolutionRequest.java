package io.crewscope.application.execution;

import io.crewscope.application.provider.ProviderBindingResolutionRequest;
import io.crewscope.domain.conversation.AgentRuntimeSession;
import io.crewscope.domain.provider.ProviderType;
import io.crewscope.domain.shared.id.PrincipalId;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Server-only inputs used to rebuild current execution authorization facts. */
public record PlatformExecutionContextResolutionRequest(
        AgentRuntimeSession runtimeSession,
        PrincipalId authenticatedPrincipalId,
        RuntimeInvocationId invocationId,
        UUID correlationId,
        Map<ProviderType, ProviderBindingResolutionRequest> providerRequirements) {

    public PlatformExecutionContextResolutionRequest {
        runtimeSession = Objects.requireNonNull(runtimeSession, "runtimeSession");
        authenticatedPrincipalId = Objects.requireNonNull(
                authenticatedPrincipalId, "authenticatedPrincipalId");
        invocationId = Objects.requireNonNull(invocationId, "invocationId");
        correlationId = Objects.requireNonNull(correlationId, "correlationId");
        AgentRuntimeSession trustedSession = runtimeSession;
        EnumMap<ProviderType, ProviderBindingResolutionRequest> safeRequirements =
                new EnumMap<>(ProviderType.class);
        Objects.requireNonNull(providerRequirements, "providerRequirements")
                .forEach((type, request) -> {
                    ProviderType requiredType = Objects.requireNonNull(
                            type, "providerRequirements key");
                    ProviderBindingResolutionRequest requiredRequest = Objects.requireNonNull(
                            request, "providerRequirements value");
                    if (requiredRequest.providerType() != requiredType) {
                        throw new IllegalArgumentException(
                                "providerRequirements key must match the request ProviderType");
                    }
                    if (!requiredRequest.organizationId().equals(
                                    trustedSession.scope().organizationId())
                            || !requiredRequest.teamId().equals(trustedSession.scope().teamId())
                            || !requiredRequest.workspaceId().equals(
                                    trustedSession.scope().workspaceId())) {
                        throw new IllegalArgumentException(
                                "Provider requirement must match the runtime Session Scope");
                    }
                    safeRequirements.put(requiredType, requiredRequest);
                });
        providerRequirements = Map.copyOf(safeRequirements);
    }
}
