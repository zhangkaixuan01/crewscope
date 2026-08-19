package io.crewscope.infrastructure.workspace.repository;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Activates host repository access only for all-in-one and Worker deployments. */
final class WorkerManagedRepositoryCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String profile = context.getEnvironment()
                .getProperty("crewscope.runtime.execution-profile", "all")
                .strip();
        return "all".equalsIgnoreCase(profile) || "worker".equalsIgnoreCase(profile);
    }
}
