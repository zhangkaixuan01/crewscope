package io.crewscope.integration.provider.collaboration;

import io.crewscope.domain.collaboration.LarkConnectionAuthorization;
import io.crewscope.domain.shared.id.PrincipalId;
import java.util.Objects;

/** Trusted current authorization and actor used for one fixed Lark operation. */
public record LarkApiCallContext(
        LarkConnectionAuthorization authorization,
        PrincipalId actor) {

    public LarkApiCallContext {
        authorization = Objects.requireNonNull(authorization, "authorization");
        actor = Objects.requireNonNull(actor, "actor");
    }

    @Override
    public String toString() {
        return "LarkApiCallContext[authorization=REDACTED, actor=REDACTED]";
    }
}
