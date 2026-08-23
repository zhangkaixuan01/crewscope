package io.crewscope.agentscope.template;

import io.agentscope.harness.agent.HarnessAgent;
import io.crewscope.agentscope.coding.CodingSpecialistFactory;
import io.crewscope.domain.agent.AgentRuntimeRole;
import io.crewscope.domain.task.TaskAgentSessionPurpose;
import java.util.Objects;
import java.util.Optional;

/** Creates Specialist Agents and routes the Coding template through the M4 Coding composition. */
public final class TemplateSpecialistAgentFactory implements TemplateAgentRuntimeFactory {

    private static final String CODING_TEMPLATE = "coding";

    private final RestrictedTemplateAgentBuilder genericBuilder;
    private final Optional<CodingSpecialistFactory> codingFactory;

    public TemplateSpecialistAgentFactory(
            RestrictedTemplateAgentBuilder genericBuilder,
            Optional<CodingSpecialistFactory> codingFactory) {
        this.genericBuilder = Objects.requireNonNull(genericBuilder, "genericBuilder");
        this.codingFactory = Objects.requireNonNull(codingFactory, "codingFactory");
    }

    @Override
    public AgentRuntimeRole runtimeRole() {
        return AgentRuntimeRole.SPECIALIST;
    }

    @Override
    public HarnessAgent create(TemplateAgentBuildRequest request) {
        TemplateAgentBuildRequest required = Objects.requireNonNull(request, "request");
        if (required.identity().kind() != TemplateAgentSessionIdentity.Kind.TASK
                || required.definition().template().runtimeRole() != runtimeRole()) {
            throw new IllegalArgumentException(
                    "Specialist Template Agent requires a Specialist Task Session");
        }
        required.identity().requireTaskPurpose(TaskAgentSessionPurpose.SPECIALIST);
        if (CODING_TEMPLATE.equals(required.definition()
                .template()
                .templateVersion()
                .key()
                .value())) {
            CodingSpecialistFactory factory = codingFactory.orElseThrow(() ->
                    new IllegalStateException("The M4 Coding runtime is unavailable on this process"));
            return factory.createResolved(
                    required.identity().requireTaskSession(),
                    required.toolkit(),
                    required.definition().primaryModel(),
                    required.definition().fallbackModel(),
                    required.definition().systemPrompt(),
                    required.definition().configuration().generateOptions());
        }
        return genericBuilder.build(required, "CrewScope template-backed Specialist Agent");
    }
}
