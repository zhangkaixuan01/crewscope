package io.crewscope.agentscope;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Proves that the production clarification Tool accepts only declared, required answers. */
class ClarificationToolTest {

  @Test
  void returnsBoundAnswersAndRejectsMissingOrUndeclaredFields() {
    ClarificationTool tool = new ClarificationTool();
    Map<String, Object> request = request();
    Map<String, Object> valid =
        Map.of(
            "request", request,
            "answers", Map.of("repository", "crewscope-java", "branch", "main"));

    ToolResultBlock result = tool.callAsync(param(valid)).block();

    assertTrue(
        result.getOutput().stream()
            .filter(TextBlock.class::isInstance)
            .map(TextBlock.class::cast)
            .map(TextBlock::getText)
            .anyMatch(text -> text.contains("crewscope-java") && text.contains("main")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            tool.callAsync(
                    param(
                        Map.of(
                            "request", request,
                            "answers", Map.of("branch", "main"))))
                .block());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            tool.callAsync(
                    param(
                        Map.of(
                            "request", request,
                            "answers", Map.of("repository", "crewscope-java", "tool", "x"))))
                .block());
  }

  private static Map<String, Object> request() {
    return Map.of(
        "schemaVersion",
        "1",
        "summary",
        "Repository details are required",
        "questions",
        List.of(
            Map.of(
                "fieldKey",
                "repository",
                "question",
                "Which repository?",
                "required",
                true,
                "choices",
                List.of()),
            Map.of(
                "fieldKey",
                "branch",
                "question",
                "Which branch?",
                "required",
                false,
                "choices",
                List.of("main"))));
  }

  private static ToolCallParam param(Map<String, Object> input) {
    ToolUseBlock block =
        ToolUseBlock.builder()
            .id("clarification-call")
            .name(ClarificationTool.NAME)
            .input(input)
            .build();
    return ToolCallParam.builder().toolUseBlock(block).input(input).build();
  }
}
