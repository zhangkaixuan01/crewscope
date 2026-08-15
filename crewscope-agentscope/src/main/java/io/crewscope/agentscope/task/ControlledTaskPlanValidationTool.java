package io.crewscope.agentscope.task;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import reactor.core.publisher.Mono;

/** Read-only Plan Mode Tool that lets the model repair invalid M3 plan syntax. */
public final class ControlledTaskPlanValidationTool extends ToolBase {

    public static final String NAME = "validate_task_plan";
    private final ControlledTaskPlanParser parser;

    public ControlledTaskPlanValidationTool(ControlledTaskPlanParser parser) {
        super(ToolBase.builder()
                .name(NAME)
                .description(
                        "Validate the complete controlled Task plan before plan_write and plan_exit. "
                                + "Required line format: " + ControlledTaskPlanParser.FORMAT)
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "content", Map.of(
                                        "type", "string",
                                        "description", "The complete controlled Task plan Markdown.")),
                        "required", List.of("content")))
                .readOnly(true)
                .concurrencySafe(true));
        this.parser = Objects.requireNonNull(parser, "parser");
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Object raw = param.getInput().get("content");
        ControlledTaskPlanParser.Validation result = parser.validate(
                raw == null ? "" : raw.toString());
        String text = result.valid()
                ? "VALID: " + result.stepCount() + " controlled steps."
                : "INVALID: " + result.message() + ". Expected: "
                        + ControlledTaskPlanParser.FORMAT;
        return Mono.just(ToolResultBlock.text(text).withIdAndName(
                param.getToolUseBlock().getId(), param.getToolUseBlock().getName()));
    }
}
