package io.crewscope.agentscope.knowledge;

import io.crewscope.agentscope.template.AgentTemplateRuntimeDefinition;
import io.crewscope.domain.task.TaskExecutionId;
import java.util.Objects;

/**
 * Complete trusted runtime graph plus the whitelisted transcript for one distillation call.
 * The transcript is already the assembler's public projection; this record re-asserts the
 * frozen bound so no upstream regression can silently stream unbounded text into a prompt.
 */
public record KnowledgeDistillerRuntimeRequest(
        AgentTemplateRuntimeDefinition definition,
        KnowledgeDistillerRuntimeSession session,
        TaskExecutionId taskExecutionId,
        int executionAttempt,
        String sanitizedSourceText,
        String requestedCategoryHint) {

    /** Mirrors the application assembler's hard bound (D7): reject, never truncate. */
    public static final int MAX_SOURCE_LENGTH = 131_072;
    public static final String MODEL_SUGGESTED_CATEGORY = "LET_THE_MODEL_SUGGEST";

    public KnowledgeDistillerRuntimeRequest {
        definition = Objects.requireNonNull(definition, "definition");
        session = Objects.requireNonNull(session, "session");
        taskExecutionId = Objects.requireNonNull(taskExecutionId, "taskExecutionId");
        if (executionAttempt < 1) {
            throw new IllegalArgumentException("executionAttempt must be positive");
        }
        sanitizedSourceText = requireTranscript(sanitizedSourceText);
        requestedCategoryHint = requireHint(requestedCategoryHint);
        if (!definition.profile().id().equals(session.distillerProfileId())
                || definition.profile().version() != session.distillerProfileVersion()
                || !definition.profile().agentPrincipalId()
                        .equals(session.distillerPrincipalId())) {
            throw new IllegalArgumentException(
                    "Knowledge Distiller request, Profile and Session must match exactly");
        }
    }

    private static String requireTranscript(String value) {
        String normalized = Objects.requireNonNull(value, "sanitizedSourceText").strip();
        if (normalized.isEmpty() || normalized.length() > MAX_SOURCE_LENGTH) {
            throw new IllegalArgumentException(
                    "Knowledge Distiller transcript must be non-empty and at most 131072 characters");
        }
        return normalized;
    }

    private static String requireHint(String value) {
        String normalized = Objects.requireNonNull(value, "requestedCategoryHint").strip();
        if (normalized.isEmpty() || normalized.length() > 64) {
            throw new IllegalArgumentException(
                    "Knowledge Distiller category hint must be bounded safe text");
        }
        return normalized;
    }
}
