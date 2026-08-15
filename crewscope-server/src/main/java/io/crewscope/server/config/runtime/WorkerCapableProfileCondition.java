package io.crewscope.server.config.runtime;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Activates Worker beans only for the explicit all and worker deployment profiles. */
public final class WorkerCapableProfileCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String value = context.getEnvironment().getProperty(
                "crewscope.runtime.execution-profile", "all");
        return "all".equalsIgnoreCase(value.strip())
                || "worker".equalsIgnoreCase(value.strip());
    }
}
