package io.crewscope.agentscope.template;

import io.agentscope.harness.agent.HarnessAgent;
import io.crewscope.domain.agent.AgentRuntimeRole;
import io.crewscope.domain.task.TaskAgentSessionPurpose;
import java.util.Objects;

/** Creates one Task-bound Team Coordinator from an exact TemplateVersion. */
public final class TemplateTeamAgentFactory implements TemplateAgentRuntimeFactory {

    private final RestrictedTemplateAgentBuilder builder;

    public TemplateTeamAgentFactory(RestrictedTemplateAgentBuilder builder) {
        this.builder = Objects.requireNonNull(builder, "builder");
    }

    @Override
    public AgentRuntimeRole runtimeRole() {
        return AgentRuntimeRole.TEAM_COORDINATOR;
    }

    @Override
    public HarnessAgent create(TemplateAgentBuildRequest request) {
        TemplateAgentBuildRequest required = Objects.requireNonNull(request, "request");
        if (required.identity().kind() != TemplateAgentSessionIdentity.Kind.TASK
                || required.definition().template().runtimeRole() != runtimeRole()) {
            throw new IllegalArgumentException(
                    "Team Template Agent requires a Team Coordinator Task Session");
        }
        TaskAgentSessionPurpose purpose = required.identity().requireTaskSession().purpose();
        if (purpose == TaskAgentSessionPurpose.SPECIALIST) {
            throw new IllegalArgumentException(
                    "Team Coordinator cannot use a Specialist Session purpose");
        }
        return builder.build(required, "CrewScope template-backed Team Coordinator");
    }
}
