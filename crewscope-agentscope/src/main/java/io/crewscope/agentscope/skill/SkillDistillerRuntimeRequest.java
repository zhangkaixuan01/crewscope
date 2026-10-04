package io.crewscope.agentscope.skill;

import io.crewscope.agentscope.template.AgentTemplateRuntimeDefinition;
import io.crewscope.domain.task.TaskExecutionId;
import java.util.Objects;

/**
 * Complete trusted runtime graph plus the whitelisted transcript for one Skill
 * distillation call (M10-A03b). The transcript is already the assembler's public
 * projection; this record re-asserts the frozen bound so no upstream regression can
 * silently stream unbounded text into a prompt. The skill key is command-owned (D4)
 * and travels only as prompt context — the model never chooses it.
 */
public record SkillDistillerRuntimeRequest(
        AgentTemplateRuntimeDefinition definition,
        SkillDistillerRuntimeSession session,
        TaskExecutionId taskExecutionId,
        int executionAttempt,
        String skillKey,
        String sanitizedSourceText) {

    /** Mirrors the application assembler's hard bound: reject, never truncate. */
    public static final int MAX_SOURCE_LENGTH = 131_072;
    public static final int MAX_SKILL_KEY_LENGTH = 128;

    public SkillDistillerRuntimeRequest {
        definition = Objects.requireNonNull(definition, "definition");
        session = Objects.requireNonNull(session, "session");
        taskExecutionId = Objects.requireNonNull(taskExecutionId, "taskExecutionId");
        if (executionAttempt < 1) {
            throw new IllegalArgumentException("executionAttempt must be positive");
        }
        skillKey = requireSkillKey(skillKey);
        sanitizedSourceText = requireTranscript(sanitizedSourceText);
        if (!definition.profile().id().equals(session.distillerProfileId())
                || definition.profile().version() != session.distillerProfileVersion()
                || !definition.profile().agentPrincipalId()
                        .equals(session.distillerPrincipalId())) {
            throw new IllegalArgumentException(
                    "Skill Distiller request, Profile and Session must match exactly");
        }
    }

    private static String requireSkillKey(String value) {
        String normalized = Objects.requireNonNull(value, "skillKey").strip();
        if (normalized.isEmpty() || normalized.length() > MAX_SKILL_KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "Skill Distiller skill key must be non-empty and at most 128 characters");
        }
        return normalized;
    }

    private static String requireTranscript(String value) {
        String normalized = Objects.requireNonNull(value, "sanitizedSourceText").strip();
        if (normalized.isEmpty() || normalized.length() > MAX_SOURCE_LENGTH) {
            throw new IllegalArgumentException(
                    "Skill Distiller transcript must be non-empty and at most 131072 characters");
        }
        return normalized;
    }
}
