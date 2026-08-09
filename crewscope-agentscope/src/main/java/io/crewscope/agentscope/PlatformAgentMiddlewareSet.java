package io.crewscope.agentscope;

import io.agentscope.core.middleware.MiddlewareBase;
import java.util.List;
import java.util.Objects;

/** Ordered platform Middleware set; the first entry is the outermost AgentScope interceptor. */
public final class PlatformAgentMiddlewareSet {

    private final List<MiddlewareBase> ordered;

    public PlatformAgentMiddlewareSet(
            PlatformRuntimeContextMiddleware runtimeContext,
            ProviderBindingSecurityMiddleware providerBindingSecurity,
            PlatformAuditMiddleware audit,
            AgentStatePreflightMiddleware statePreflight) {
        this(List.of(runtimeContext, providerBindingSecurity, audit, statePreflight));
    }

    PlatformAgentMiddlewareSet(
            PlatformRuntimeContextMiddleware runtimeContext,
            ProviderBindingSecurityMiddleware providerBindingSecurity,
            PlatformAuditMiddleware audit) {
        this(List.of(runtimeContext, providerBindingSecurity, audit));
    }

    PlatformAgentMiddlewareSet(List<? extends MiddlewareBase> ordered) {
        this.ordered = List.copyOf(Objects.requireNonNull(ordered, "ordered"));
        if (this.ordered.isEmpty() || this.ordered.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("ordered Middleware must not be empty");
        }
    }

    public List<MiddlewareBase> ordered() {
        return ordered;
    }
}
