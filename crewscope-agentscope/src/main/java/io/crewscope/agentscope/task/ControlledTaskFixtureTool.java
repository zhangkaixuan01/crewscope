package io.crewscope.agentscope.task;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import reactor.core.publisher.Mono;

/** Deterministic, in-process M3 Step Tool with no Provider, filesystem or network side effects. */
public final class ControlledTaskFixtureTool extends ToolBase {

    public ControlledTaskFixtureTool(String name) {
        super(ToolBase.builder()
                .name(requireName(name))
                .description("Execute a deterministic CrewScope M3 durability fixture step.")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "input", Map.of(
                                        "type", "string",
                                        "description", "Bounded fixture input.")),
                        "required", List.of("input")))
                // Mark it mutating for AgentScope Plan Mode even though the M3 implementation has
                // no external side effect. This prevents execution before plan_exit approval.
                // Domain Step state is changed later by an application service, never here.
                .readOnly(false)
                .concurrencySafe(true));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        String input = Objects.toString(param.getInput().get("input"), "").strip();
        String result = input.isEmpty() || input.length() > 1_000
                ? "FIXTURE_REJECTED: input must contain 1 to 1000 characters."
                : "FIXTURE_OK: " + getName();
        return Mono.just(ToolResultBlock.text(result).withIdAndName(
                param.getToolUseBlock().getId(), param.getToolUseBlock().getName()));
    }

    private static String requireName(String name) {
        String required = Objects.requireNonNull(name, "name");
        if (!io.crewscope.application.task.TaskPlanPublicationService.M3_CONTROLLED_TOOLS
                .contains(required)) {
            throw new IllegalArgumentException("name is not an M3 controlled Fixture Tool");
        }
        return required;
    }
}
