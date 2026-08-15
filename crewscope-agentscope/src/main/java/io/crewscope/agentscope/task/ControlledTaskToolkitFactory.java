package io.crewscope.agentscope.task;

import io.agentscope.core.tool.Toolkit;
import io.crewscope.application.task.TaskPlanPublicationService;
import java.util.Objects;
import java.util.function.Supplier;

/** Creates a fresh allow-listed Toolkit; Provider write Tools cannot be injected into M3. */
public final class ControlledTaskToolkitFactory implements Supplier<Toolkit> {

    private final ControlledTaskPlanParser parser;

    public ControlledTaskToolkitFactory(ControlledTaskPlanParser parser) {
        this.parser = Objects.requireNonNull(parser, "parser");
    }

    @Override
    public Toolkit get() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(new ControlledTaskPlanValidationTool(parser));
        TaskPlanPublicationService.M3_CONTROLLED_TOOLS.stream()
                .sorted()
                .map(ControlledTaskFixtureTool::new)
                .forEach(toolkit::registerAgentTool);
        return toolkit;
    }
}
