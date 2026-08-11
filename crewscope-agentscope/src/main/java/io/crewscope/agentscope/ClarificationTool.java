package io.crewscope.agentscope;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.util.JsonUtils;
import io.crewscope.application.conversation.ClarificationAnswers;
import io.crewscope.application.conversation.ClarificationRequestV1;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import reactor.core.publisher.Mono;

/** Read-only built-in Tool that pauses until CrewScope binds validated field-keyed answers. */
public final class ClarificationTool extends ToolBase {

  public static final String NAME = "request_clarification";

  public ClarificationTool() {
    super(
        ToolBase.builder()
            .name(NAME)
            .description("Pause and ask the user for structured clarification")
            .inputSchema(schema())
            .readOnly(true)
            .concurrencySafe(true));
  }

  @Override
  public Mono<PermissionDecision> checkPermissions(
      Map<String, Object> toolInput, PermissionContextState context) {
    return Mono.just(PermissionDecision.ask("Clarification answer is required"));
  }

  @Override
  public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
    try {
      Map<String, String> answers = validatedAnswers(param.getInput());
      return Mono.just(
          ToolResultBlock.text(
              "clarification_answers=" + JsonUtils.getJsonCodec().toJson(answers)));
    } catch (RuntimeException failure) {
      return Mono.error(failure);
    }
  }

  private static Map<String, String> validatedAnswers(Map<String, Object> input) {
    Map<?, ?> request = requireMap(input.get("request"), "request");
    if (!ClarificationRequestV1.SCHEMA_VERSION.equals(request.get("schemaVersion"))) {
      throw new IllegalArgumentException("Clarification schemaVersion must be 1");
    }
    Object rawQuestions = request.get("questions");
    if (!(rawQuestions instanceof List<?> questions)
        || questions.isEmpty()
        || questions.size() > 10) {
      throw new IllegalArgumentException("Clarification questions are invalid");
    }
    Set<String> declared = new LinkedHashSet<>();
    Set<String> required = new LinkedHashSet<>();
    for (Object rawQuestion : questions) {
      Map<?, ?> question = requireMap(rawQuestion, "question");
      Object rawFieldKey = question.get("fieldKey");
      if (!(rawFieldKey instanceof String fieldKey) || fieldKey.isBlank()) {
        throw new IllegalArgumentException("Clarification fieldKey is invalid");
      }
      if (!declared.add(fieldKey)) {
        throw new IllegalArgumentException("Clarification fieldKey must be unique");
      }
      if (Boolean.TRUE.equals(question.get("required"))) {
        required.add(fieldKey);
      }
    }
    Map<?, ?> rawAnswers = requireMap(input.get("answers"), "answers");
    Map<String, String> answers = new LinkedHashMap<>();
    rawAnswers.forEach(
        (rawKey, rawValue) -> {
          if (!(rawKey instanceof String key)
              || !declared.contains(key)
              || !(rawValue instanceof String value)
              || value.isBlank()) {
            throw new IllegalArgumentException(
                "Clarification answers must match declared field keys");
          }
          answers.put(key, value.strip());
        });
    ClarificationAnswers normalized = new ClarificationAnswers(answers);
    if (!normalized.values().keySet().containsAll(required)) {
      throw new IllegalArgumentException("Required clarification answers are missing");
    }
    return normalized.values();
  }

  private static Map<?, ?> requireMap(Object value, String field) {
    if (value instanceof Map<?, ?> map) {
      return map;
    }
    throw new IllegalArgumentException("Clarification " + field + " must be an object");
  }

  private static Map<String, Object> schema() {
    Map<String, Object> question =
        Map.of(
            "type",
            "object",
            "properties",
            Map.of(
                "fieldKey", Map.of("type", "string", "pattern", "[a-z][a-z0-9_]{0,63}"),
                "question", Map.of("type", "string", "minLength", 1, "maxLength", 500),
                "context", Map.of("type", "string", "maxLength", 1_000),
                "required", Map.of("type", "boolean"),
                "choices",
                    Map.of(
                        "type", "array", "maxItems", 5, "items", Map.of("type", "string"))),
            "required",
            List.of("fieldKey", "question", "required", "choices"));
    Map<String, Object> request =
        Map.of(
            "type",
            "object",
            "properties",
            Map.of(
                "schemaVersion", Map.of("type", "string", "enum", List.of("1")),
                "summary", Map.of("type", "string", "minLength", 1, "maxLength", 1_000),
                "questions",
                    Map.of(
                        "type",
                        "array",
                        "minItems",
                        1,
                        "maxItems",
                        10,
                        "items",
                        question)),
            "required",
            List.of("schemaVersion", "summary", "questions"));
    return Map.of(
        "type",
        "object",
        "properties",
        Map.of(
            "request", request,
            "answers", Map.of("type", "object", "additionalProperties", Map.of("type", "string"))),
        "required",
        List.of("request"));
  }
}
