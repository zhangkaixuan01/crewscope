package io.crewscope.domain.agent;

import io.crewscope.domain.shared.error.DomainError;
import io.crewscope.domain.shared.error.DomainErrorCode;
import io.crewscope.domain.shared.error.DomainException;
import java.util.Map;
import java.util.Objects;

/** Safe fail-closed result for a model selection or execution configuration. */
public final class AgentModelPreflightException extends DomainException {

    public AgentModelPreflightException(AgentModelPreflightRejectionCode reason) {
        super(new DomainError(
                DomainErrorCode.POLICY_DENIED,
                "Agent model preflight denied",
                Map.of("reason", Objects.requireNonNull(reason, "reason").name())));
    }

    public AgentModelPreflightRejectionCode reason() {
        return AgentModelPreflightRejectionCode.valueOf(error().details().get("reason"));
    }
}
