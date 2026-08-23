package io.crewscope.agentscope.template;

import io.agentscope.harness.agent.HarnessAgent;
import io.crewscope.domain.agent.AgentRuntimeRole;
import java.util.Objects;

/** Creates one conversation-bound Personal Assistant from an exact TemplateVersion. */
public final class TemplatePersonalAgentFactory implements TemplateAgentRuntimeFactory {

    private final RestrictedTemplateAgentBuilder builder;

    public TemplatePersonalAgentFactory(RestrictedTemplateAgentBuilder builder) {
        this.builder = Objects.requireNonNull(builder, "builder");
    }

    @Override
    public AgentRuntimeRole runtimeRole() {
        return AgentRuntimeRole.PERSONAL_ASSISTANT;
    }

    @Override
    public HarnessAgent create(TemplateAgentBuildRequest request) {
        TemplateAgentBuildRequest required = Objects.requireNonNull(request, "request");
        if (required.identity().kind() != TemplateAgentSessionIdentity.Kind.CONVERSATION
                || required.definition().template().runtimeRole() != runtimeRole()) {
            throw new IllegalArgumentException(
                    "Personal Template Agent requires a Personal Assistant Conversation Session");
        }
        return builder.build(required, "CrewScope template-backed Personal Assistant");
    }
}
