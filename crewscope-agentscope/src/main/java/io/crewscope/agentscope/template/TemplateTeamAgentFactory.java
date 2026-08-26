package io.crewscope.agentscope.template;

import io.agentscope.harness.agent.HarnessAgent;
import io.crewscope.domain.agent.AgentRuntimeRole;
import io.crewscope.domain.task.TaskAgentSessionPurpose;
import io.crewscope.domain.teamobserver.TeamObserverTemplate;
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
        if (required.definition().template().runtimeRole() != runtimeRole()) {
            throw new IllegalArgumentException(
                    "Team Template Agent requires the Team Coordinator runtime role");
        }
        if (required.identity().kind() == TemplateAgentSessionIdentity.Kind.TASK) {
            TaskAgentSessionPurpose purpose = required.identity().requireTaskSession().purpose();
            if (purpose == TaskAgentSessionPurpose.SPECIALIST) {
                throw new IllegalArgumentException(
                        "Team Coordinator cannot use a Specialist Session purpose");
            }
        } else if (required.identity().kind() == TemplateAgentSessionIdentity.Kind.TEAM_OBSERVER) {
            TeamObserverTemplate.requireDefinition(required.definition().template());
        } else {
            throw new IllegalArgumentException(
                    "Team Template Agent requires a Task or Team Observer Session");
        }
        return builder.build(required, "CrewScope template-backed Team Coordinator");
    }
}
