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
        // The protocol repair sits innermost (closest to the model call): the interrupt/compaction
        // orphaned-tool-message defect (M9b-Q02 defect 10) must be fixed on the send side after
        // every platform concern has run, and it mutates nothing the outer middleware relies on.
        this(List.of(runtimeContext, providerBindingSecurity, audit, statePreflight,
                new ToolMessageProtocolRepairMiddleware()));
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
